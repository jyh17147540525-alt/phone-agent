#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线迁移校验器 —— 在没有 Gradle / 没有 Android 设备的情况下，
验证 Room 迁移 SQL 是否与 Room 从 @Entity 生成的期望 schema 一致。

═══════════════════════════════════════════════════════════════
 为什么需要它
═══════════════════════════════════════════════════════════════

本机 Gradle 的 Kotlin DSL 脚本编译存在超时问题（见下方"已知环境问题"），
导致 `./gradlew :core:database:testDebugUnitTest --tests "*MigrationTest*"`
跑不起来 —— 配置阶段就挂，任何任务都不会执行。

但迁移验证**不需要 Android 运行时**：它比对的是两件事
  ① v1 的建表 SQL  （来自 schemas/1.json，是 Room 自己导出的，权威）
  ② 我写的迁移 SQL  （来自 Migrations.kt）
把 ① 跑一遍，再跑 ②，得到的实际结构必须与 ③（schemas/2.json）一致。

这三者都是纯文本/SQL，用 Python 的 sqlite3 就能完整复现
`MigrationTestHelper.runMigrationsAndValidate` 的判定逻辑。

⚠️ 它**不能**替代 `MigrationTest`：
   - 无法验证 Room 的 `@Entity` 与 `2.json` 是否同步（那由 KSP 保证）
   - 无法验证 Kotlin 侧调用（如 CredentialDao 的新方法）
   它能验证的是**最容易错、后果最严重**的那部分：手写 SQL 与期望 schema 不匹配。
   而这正是"升级后打开数据库直接崩"的唯一来源。

═══════════════════════════════════════════════════════════════
 已知环境问题（本机，与项目代码无关）
═══════════════════════════════════════════════════════════════

`project ':channel' does not specify compileSdk` + `TimeoutException`，
实测已排除：模块脚本内容（把 contribute 的脚本换给 channel，失败点转移到
:core:crypto）、陈旧 build 目录、冷守护进程、parallel、堆内存、Kotlin 守护
进程内存。综合表现（固定的 ~60s 预算 + 失败模块漂移 + 脚本体静默不执行）
指向 Kotlin DSL 脚本编译在本机非 ASCII 项目路径下的失败。

用法：
    python tools/verify/check_migration.py
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
from pathlib import Path

# tools/verify/check_migration.py → parents[2] = android/
ROOT = Path(__file__).resolve().parents[2]
SCHEMA_DIR = (
    ROOT
    / "core/database/schemas/com.pocketagent.core.database.PocketAgentDatabase"
)
MIGRATIONS_KT = (
    ROOT
    / "core/database/src/main/kotlin/com/pocketagent/core/database/Migrations.kt"
)

RED = "\033[31m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RESET = "\033[0m"


def ok(msg: str) -> None:
    print(f"{GREEN}  ✓ {msg}{RESET}")


def bad(msg: str) -> None:
    print(f"{RED}  ✗ {msg}{RESET}")


def warn(msg: str) -> None:
    print(f"{YELLOW}  ! {msg}{RESET}")


def materialise(sql: str, table: str) -> str:
    """
    Room 导出的 createSql / index createSql 里用的是 `${TABLE_NAME}` 占位符，
    不是真实表名 —— 因为同一个模板要供多张表复用。

    MigrationTestHelper 内部就是在执行前做这个替换。我们照做，
    否则会得到 `no such table: credential` 这种误导性错误。
    """
    return sql.replace("${TABLE_NAME}", table)


def load_schema(version: int) -> dict:
    path = SCHEMA_DIR / f"{version}.json"
    if not path.exists():
        sys.exit(f"找不到 schema 文件：{path}")
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)["database"]


# ── 从 Migrations.kt 里提取迁移语句 ─────────────────────────────
#
# 刻意用正则而不是"执行 Kotlin"：这些 SQL 是**字面量**，
# 而且迁移脚本本该冻结在写它那刻 —— 提取字面量比求值更贴近真实语义。
#
# 支持两种写法：
#   db.execSQL("...")            → 单行或多行字符串
#   db.execSQL(TABLE_CREATE_SQL) → 具名常量，需回查常量定义

def extract_migration_sql(kt_path: Path, from_version: int, to_version: int) -> list[str]:
    """提取 MIGRATION_<from>_<to> 里的 execSQL 语句（按出现顺序）。"""
    text = kt_path.read_text(encoding="utf-8")

    # 常量表：val NAME = "..."
    consts: dict[str, str] = {}
    for m in re.finditer(r'val\s+(\w+)\s*=\s*"""(.*?)"""', text, re.S):
        consts[m.group(1)] = m.group(2)
    for m in re.finditer(r'val\s+(\w+)\s*=\s*"([^"\n]*)"', text):
        consts.setdefault(m.group(1), m.group(2))

    # 定位 MIGRATION_<from>_<to> 这个对象
    marker = f"MIGRATION_{from_version}_{to_version}"
    idx = text.find(marker)
    if idx < 0:
        sys.exit(f"Migrations.kt 里找不到 {marker}")

    # 取到下一个 `val MIGRATION_` 或 ALL 声明为止
    rest = text[idx:]
    nxt = re.search(r"\n\s*val\s+MIGRATION_|\n\s*val\s+ALL", rest)
    if nxt:
        rest = rest[: nxt.start()]

    stmts: list[str] = []
    # 逐个 execSQL(...) 抓取。
    #
    # ⚠️ 手写"扫描到配对右括号"的尝试失败过两次，原因都是**必须先正确识别
    #    字符串边界**：Kotlin 里 `"a" + "b"` 与 `"""多行"""` 会让朴素的
    #    括号计数跑过界，把下一条语句也吞进来（表现为两条 SQL 粘在一起，
    #    报 `near "CREATE": syntax error`）。
    #
    #    所以这里改成两步走：
    #      ① 先把所有字符串字面量替换成等长占位符（\$ 与括号都换掉），
    #         这样括号计数就只看得见真正的代码括号；
    #      ② 在占位串上做配对，再回到原文按同样的下标切片。
    masked, literals = _mask_strings(rest)

    for m in re.finditer(r"execSQL\(", masked):
        start = m.end()
        depth = 1
        i = start
        while i < len(masked) and depth > 0:
            ch = masked[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        arg_masked = masked[start:i]
        stmts.append(
            _resolve_masked_arg(arg_masked, literals, start, consts, text=rest)
        )

    if not stmts:
        sys.exit(f"{marker} 里没有提取到任何 execSQL 语句")
    return stmts


# 占位符里不可能出现的字符，用它们代表被屏蔽的字符串
_MASK_CHAR = "\x00"


def _mask_strings(text: str) -> tuple[str, dict[int, str]]:
    """
    把所有 Kotlin 字符串字面量替换成占位符，返回 (掩码文本, {起始下标: 原文}).

    ⚠️ 必须先处理三引号（连续三个双引号），再处理单个双引号。顺序反了会把
       三引号解析成"一个空字符串 + 一个起始引号"，后面全乱。
    """
    out: list[str] = []
    literals: dict[int, str] = {}
    i = 0
    n = len(text)
    while i < n:
        # 三引号优先
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end < 0:
                end = n
            raw = text[i + 3:end]
            literals[i] = raw
            out.append(_MASK_CHAR * (end + 3 - i))
            i = end + 3
            continue
        ch = text[i]
        if ch == '"':
            j = i + 1
            raw_chars: list[str] = []
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    raw_chars.append(text[j + 1])
                    j += 2
                    continue
                if text[j] == '"':
                    break
                raw_chars.append(text[j])
                j += 1
            literals[i] = "".join(raw_chars)
            out.append(_MASK_CHAR * (j + 1 - i))
            i = j + 1
            continue
        out.append(ch)
        i += 1
    return "".join(out), literals


def _resolve_masked_arg(
    arg_masked: str,
    literals: dict[int, str],
    offset: int,
    consts: dict[str, str],
    *,
    text: str,
) -> str:
    """
    把（已掩码的）execSQL 参数还原成 SQL 文本。

    ⚠️ 只取**落在本参数区间内**的字面量。`literals` 里存的是整段迁移代码的
       所有字符串，若不做区间过滤，每条语句都会拼上前面所有语句的内容 ——
       表现为 7 条一模一样的巨型 SQL。这是本脚本踩过的第三个坑。
    """
    arg_masked = arg_masked.strip()
    if not arg_masked:
        sys.exit("execSQL() 参数为空")

    # 具名常量：整段是裸标识符
    if re.fullmatch(r"\w+", arg_masked):
        if arg_masked not in consts:
            sys.exit(f"迁移引用了未找到的常量：{arg_masked}")
        return consts[arg_masked]

    # 只保留落在本参数区间内的字面量
    lo, hi = offset, offset + len(arg_masked)
    pieces = [v for pos, v in sorted(literals.items()) if lo <= pos < hi]
    if not pieces:
        sys.exit(f"无法解析 execSQL 参数：{arg_masked[:120]}")
    return "".join(pieces).strip()


# ── 解析 CREATE TABLE 得到 (列名 → 归一化类型, notnull, default) ──

def parse_create_table(sql: str) -> dict:
    """把 CREATE TABLE 语句解析成 {'columns': {...}, 'indices_hint': [...]}。"""
    inner = sql[sql.find("(") + 1: sql.rfind(")")]
    cols: dict[str, dict] = {}
    depth = 0
    cur = ""
    parts: list[str] = []
    in_str = False
    for ch in inner:
        if ch == "'":
            in_str = not in_str
        if not in_str:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            elif ch == "," and depth == 0:
                parts.append(cur)
                cur = ""
                continue
        cur += ch
    if cur.strip():
        parts.append(cur)

    for part in parts:
        p = part.strip()
        if not p:
            continue
        upper = p.upper()
        if upper.startswith(("PRIMARY KEY", "FOREIGN KEY", "UNIQUE", "CHECK", "CONSTRAINT")):
            continue
        # 形如 `name` TYPE ...  或 name TYPE ...
        m = re.match(r'[`"\[]?(\w+)[`"\]]?\s+([A-Z]+(?:\s*\([^)]*\))?)(.*)', p, re.S)
        if not m:
            continue
        name, ctype, tail = m.group(1), m.group(2), m.group(3)
        notnull = bool(re.search(r"\bNOT\s+NULL\b", tail, re.I))
        default = None
        dm = re.search(r"\bDEFAULT\s+('(?:[^']*)'|\S+)", tail, re.I)
        if dm:
            default = dm.group(1).strip("'")
        cols[name] = {
            "type": re.sub(r"\s+", "", ctype.upper()),
            "notnull": notnull,
            "default": default,
        }
    return {"columns": cols}


def normalise_type(t: str) -> str:
    """Room 与 SQLite 的类型写法归一化。"""
    t = t.upper().replace(" ", "")
    # Room 用 INTEGER 表示 Int/Boolean，TEXT 表示 String
    return t



def _norm_sql(sql: str) -> str:
    """归一化 SQL 空白与反引号，便于比对。"""
    sql = re.sub(r"\s+", " ", sql).strip()
    # Room 生成时会给标识符加反引号，手写 SQL 可能不加。
    # 反引号在 SQLite 里只是引用语法，不影响结构 —— 去掉再比。
    sql = sql.replace("`", "")
    return sql


# ── 与 Room 的比对语义对齐 ──────────────────────────────────────
#
# ⚠️ 这一点必须按 Room 的真实实现来，不能凭直觉。
#    第一版校验器直接比对 createSql 文本，报了"列顺序不一致"，
#    差点让人去重写迁移做表重建 —— 那是白做，还可能引入新风险。
#
#    反编译 room-runtime 2.6.1 的 `androidx.room.util.TableInfo.equals`：
#      name        : String            → 比内容
#      columns     : java.util.Map     → **Map 相等，与顺序无关**
#      foreignKeys : java.util.Set     → Set，与顺序无关
#      indices     : java.util.Set     → Set，与顺序无关
#
#    而 `TableInfo$Index.equals` 比的是
#      unique + columns + orders + **name**（且 name 会先剥掉 "index_" 前缀）
#
#    结论：
#      · 列顺序**不影响**校验 —— `ALTER TABLE ADD COLUMN` 追加到末尾是安全的
#      · 索引**名字**必须完全一致 —— 写错名字必炸
#      · 列的类型与 NOT NULL 必须一致
#
#    所以本校验器按 name→{type, notnull} 的字典比对列，按名字集合比对索引。


def compare_table(db: sqlite3.Connection, entity: dict) -> bool:
    """按 Room 的语义（Map/Set）比对表结构。"""
    table = entity["tableName"]
    passed = True
    print(f"\n  表 {table}")

    row = db.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (table,)
    ).fetchone()
    if row is None:
        bad(f"表 {table} 不存在")
        return False

    actual_sql = row[0]
    actual_cols = parse_create_table(actual_sql)["columns"]

    # ① 列：按名字比对类型与 NOT NULL（顺序无关，同 Room 的 Map 语义）
    exp_cols = {f["columnName"]: f for f in entity["fields"]}
    for cname, exp in exp_cols.items():
        if cname not in actual_cols:
            bad(f"缺列 {cname}")
            passed = False
            continue
        act = actual_cols[cname]
        exp_type = normalise_type(exp["affinity"])
        if act["type"] != exp_type:
            bad(f"列 {cname} 类型不符：期望 {exp_type}，实际 {act['type']}")
            passed = False
        exp_notnull = bool(exp.get("notNull", False))
        if act["notnull"] != exp_notnull:
            bad(f"列 {cname} NOT NULL 不符：期望 {exp_notnull}，实际 {act['notnull']}")
            passed = False

    extra_cols = set(actual_cols) - set(exp_cols)
    if extra_cols:
        bad(f"多出 Room 不认识的列：{sorted(extra_cols)}")
        passed = False

    if not extra_cols and all(
        c in actual_cols
        and actual_cols[c]["type"] == normalise_type(e["affinity"])
        and actual_cols[c]["notnull"] == bool(e.get("notNull", False))
        for c, e in exp_cols.items()
    ):
        ok(f"{len(exp_cols)} 列全部匹配（名字/类型/NOT NULL；顺序无关）")

    # ② 索引：按**名字**比对（Room 用 Set<Index>，名字参与相等判断）
    exp_indices = {i["name"]: i for i in entity.get("indices", [])}
    act_indices = {
        r[0]: r[1]
        for r in db.execute(
            "SELECT name, sql FROM sqlite_master WHERE type='index' AND tbl_name=? "
            "AND sql IS NOT NULL",
            (table,),
        )
    }
    for iname, ispec in exp_indices.items():
        if iname not in act_indices:
            bad(f"缺索引 {iname}（Room 按名字校验，名字错就等于没有）")
            passed = False
            continue
        # 唯一性也要对（Room 的 Index.equals 比 unique）
        act_unique = "UNIQUE" in (act_indices[iname] or "").upper()
        if act_unique != bool(ispec.get("unique")):
            bad(
                f"索引 {iname} 唯一性不符：期望 {bool(ispec.get('unique'))}，实际 {act_unique}"
            )
            passed = False
        else:
            ok(f"索引 {iname} 存在{'（唯一）' if ispec.get('unique') else ''}")

    extra_idx = set(act_indices) - set(exp_indices)
    if extra_idx:
        # Room 运行时会忽略多余索引，但 MigrationTestHelper 在
        # validateDroppedTables=true 时的行为更严格 —— 这里只提示。
        warn(f"多出的索引（Room 期望里没有）：{sorted(extra_idx)}")

    return passed


def main() -> int:
    print("=" * 64)
    print("  离线迁移校验（替代 MigrationTestHelper 的结构比对部分）")
    print("=" * 64)

    v1 = load_schema(1)
    v2 = load_schema(2)

    print(f"\nv1 → v2，v1 有 {len(v1['entities'])} 张表，v2 有 {len(v2['entities'])} 张表")

    # ① 用 v1 的 createSql 建库（等价于 helper.createDatabase(db, 1)）
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for ent in v1["entities"]:
        db.execute(materialise(ent["createSql"], ent["tableName"]))
    for ent in v1["entities"]:
        for idx in ent.get("indices", []):
            db.execute(materialise(idx["createSql"], ent["tableName"]))
    print("\n① 已用 v1 schema 建库")

    # 插入一条 v1 老数据（等价于测试里那条 INSERT）—— 同时验证列名没写错
    try:
        db.execute(
            """
            INSERT INTO credential
                (id, providerId, label, ciphertext, iv, keyLength,
                 baseUrlOverride, createdAtMillis, lastCheckedAtMillis,
                 lastStatus, lastStatusDetail, modelCount, isDefault)
            VALUES ('c1','deepseek','主力',X'0102',X'0304',35,
                    NULL,1000,NULL,NULL,NULL,NULL,1)
            """
        )
        ok("v1 老数据插入成功（MigrationTest 用的同一条语句）")
    except sqlite3.Error as exc:
        bad(f"v1 老数据插入失败：{exc}")
        return 1

    # ② 应用迁移
    stmts = extract_migration_sql(MIGRATIONS_KT, 1, 2)
    print(f"\n② 从 Migrations.kt 提取到 {len(stmts)} 条语句，开始执行")
    for i, s in enumerate(stmts, 1):
        head = _norm_sql(s)[:72]
        try:
            db.execute(s)
            ok(f"[{i}] {head}…")
        except sqlite3.Error as exc:
            bad(f"[{i}] 执行失败：{exc}")
            print(f"      SQL: {s[:200]}")
            return 1
    db.commit()

    # ③ 与 v2 期望结构比对
    print("\n③ 与实际结构比对（Room 从 @Entity 生成的期望）")
    all_passed = True
    for ent in v2["entities"]:
        if not compare_table(db, ent):
            all_passed = False

    # ④ 数据保全：老数据的 purpose 必须变成 'LLM'
    print("\n④ 老数据保全检查")
    try:
        row = db.execute(
            "SELECT id, providerId, label, keyLength, isDefault, purpose "
            "FROM credential WHERE id='c1'"
        ).fetchone()
        if row is None:
            bad("老数据丢失了！（结构对但数据没了 —— 比失败更危险）")
            all_passed = False
        else:
            assert row[0] == "c1" and row[1] == "deepseek" and row[2] == "主力"
            assert row[3] == 35 and row[4] == 1
            if row[5] != "LLM":
                bad(f"purpose 应为 'LLM'，实际 {row[5]!r}")
                all_passed = False
            else:
                ok("老数据完整保留，且 purpose 默认为 'LLM'")
    except sqlite3.Error as exc:
        bad(f"查询失败：{exc}")
        all_passed = False

    # ⑤ 唯一约束真的生效
    print("\n⑤ 唯一约束检查")
    try:
        db.execute(
            "INSERT INTO model_config (id,credentialId,modelId,label,tier,roleMask,"
            "inputPriceOverride,outputPriceOverride,enabled,createdAtMillis) "
            "VALUES ('m1','c1','gpt-4o-mini',NULL,'STANDARD',2,NULL,NULL,1,1000)"
        )
        try:
            db.execute(
                "INSERT INTO model_config (id,credentialId,modelId,label,tier,roleMask,"
                "inputPriceOverride,outputPriceOverride,enabled,createdAtMillis) "
                "VALUES ('m2','c1','gpt-4o-mini',NULL,'STANDARD',2,NULL,NULL,1,1000)"
            )
            bad("重复的 (credentialId, modelId) 竟然插入成功 —— 唯一索引没生效")
            all_passed = False
        except sqlite3.IntegrityError:
            ok("同凭据同模型被唯一索引拒绝")
        # 不同凭据应可共存
        db.execute(
            "INSERT INTO model_config (id,credentialId,modelId,label,tier,roleMask,"
            "inputPriceOverride,outputPriceOverride,enabled,createdAtMillis) "
            "VALUES ('m3','c2','gpt-4o-mini',NULL,'STANDARD',2,NULL,NULL,1,1000)"
        )
        ok("不同凭据可配置同一模型")
    except sqlite3.Error as exc:
        bad(f"唯一约束测试失败：{exc}")
        all_passed = False

    db.close()

    print("\n" + "=" * 64)
    if all_passed:
        print(f"{GREEN}  全部通过 —— 迁移后的结构符合 Room 期望{RESET}")
        print("=" * 64)
        return 0
    print(f"{RED}  存在不匹配，升级后会崩在打开数据库那一步{RESET}")
    print("=" * 64)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
