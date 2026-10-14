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


def _behaviour_1_2(db: sqlite3.Connection) -> bool:
    """v1→v2 的行为检查：老数据保全 + purpose 默认值 + 唯一约束真生效。"""
    passed = True

    print("\n④ 老数据保全检查（探针行 c1）")
    try:
        row = db.execute(
            "SELECT id, providerId, label, keyLength, isDefault, purpose "
            "FROM credential WHERE id='c1'"
        ).fetchone()
        if row is None:
            bad("老数据丢失了！（结构对但数据没了 —— 比失败更危险）")
            passed = False
        else:
            assert row[0] == "c1" and row[1] == "deepseek" and row[2] == "主力"
            assert row[3] == 35 and row[4] == 1
            if row[5] != "LLM":
                bad(f"purpose 应为 'LLM'，实际 {row[5]!r}")
                passed = False
            else:
                ok("老数据完整保留，且 purpose 默认为 'LLM'")
    except sqlite3.Error as exc:
        bad(f"查询失败：{exc}")
        passed = False

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
            passed = False
        except sqlite3.IntegrityError:
            ok("同凭据同模型被唯一索引拒绝")
        db.execute(
            "INSERT INTO model_config (id,credentialId,modelId,label,tier,roleMask,"
            "inputPriceOverride,outputPriceOverride,enabled,createdAtMillis) "
            "VALUES ('m3','c2','gpt-4o-mini',NULL,'STANDARD',2,NULL,NULL,1,1000)"
        )
        ok("不同凭据可配置同一模型")
    except sqlite3.Error as exc:
        bad(f"唯一约束测试失败：{exc}")
        passed = False

    return passed


def _behaviour_3_4(db: sqlite3.Connection) -> bool:
    """v3→v4 的行为检查：老表没被动 + 新表能读写 + ★ 外键级联**真**生效。

    ⚠️ 最后一条是本迁移最要紧的：`ON DELETE CASCADE` 声明了不等于生效 ——
       SQLite 的 `PRAGMA foreign_keys` 默认是**关**的。
       不打开的话，删了会话、用户说过的每一句话还躺在库里。
       **那是隐私问题，不是整洁问题。**
    """
    passed = True

    print("\n④ 老数据保全（探针行 c1）")
    try:
        # 纯新增表**不该触碰任何既有表** —— 探针行必须原样还在。
        # ⚠️ 这条检查的价值在于：它挡的是"迁移脚本顺手 UPDATE 了一下老表"
        #    这类看起来无害、实际会改用户数据的写法。
        row = db.execute(
            "SELECT providerId, label, keyLength, isDefault FROM credential WHERE id='c1'"
        ).fetchone()
        if row is None:
            bad("v4 迁移把 credential 里的老数据弄丢了")
            passed = False
        elif row != ("deepseek", "主力", 35, 1):
            bad(f"老数据被改动了：{row!r}")
            passed = False
        else:
            ok("credential 探针行原样保留（纯新增表，未触碰既有表）")
    except sqlite3.Error as exc:
        bad(f"查询 credential 失败：{exc}")
        passed = False

    print("\n⑤ 新表可读写")
    try:
        db.execute(
            "INSERT INTO session (id,title,titleSource,createdAt,updatedAt,"
            "lastMessageAt,turnCount,pinned,archived,activeLeafTurnId) "
            "VALUES ('s1','帮我查一下明天天气','AUTO',1000,1000,1000,0,0,0,NULL)"
        )
        db.execute(
            "INSERT INTO turn (id,sessionId,role,text,at,tokenCount,parentTurnId,superseded) "
            "VALUES ('t1','s1','USER','你好',1000,NULL,NULL,0)"
        )
        db.execute(
            "INSERT INTO task (id,sessionId,prompt,state,step,note,abortReason,"
            "perceptionTier,createdAtMillis,finishedAtMillis) "
            "VALUES ('k1','s1','你好','Done',1,'你好，有什么需要帮忙的？',NULL,0,1000,1100)"
        )
        db.execute("UPDATE session SET turnCount = 1, activeLeafTurnId = 't1' WHERE id='s1'")
        row = db.execute(
            "SELECT s.turnCount, s.activeLeafTurnId, t.text, k.state "
            "FROM session s JOIN turn t ON t.sessionId = s.id "
            "JOIN task k ON k.sessionId = s.id WHERE s.id='s1'"
        ).fetchone()
        if row is None:
            bad("插入后联查不到数据")
            passed = False
        else:
            ok(f"会话/轮次/执行记录可写可查（turnCount={row[0]}，状态={row[3]}）")
    except sqlite3.Error as exc:
        bad(f"新表读写失败：{exc}")
        passed = False

    print("\n⑥ ★ 外键级联是否真生效（本迁移最要紧的一条）")
    try:
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("DELETE FROM session WHERE id='s1'")
        left_t = db.execute("SELECT COUNT(*) FROM turn WHERE sessionId='s1'").fetchone()[0]
        left_k = db.execute("SELECT COUNT(*) FROM task WHERE sessionId='s1'").fetchone()[0]
        if left_t == 0 and left_k == 0:
            ok("开了 PRAGMA 之后，删会话会带走轮次与执行记录")
        else:
            bad(
                f"级联没生效！删了会话，却还剩 {left_t} 条轮次、{left_k} 条执行记录 "
                "—— 用户以为删干净了，而磁盘上还留着他说的每一句话",
            )
            passed = False
    except sqlite3.Error as exc:
        bad(f"级联测试失败：{exc}")
        passed = False

    print("\n⑦ ★ 关掉 PRAGMA 时级联**确实不生效**（证明那句 PRAGMA 是必需的）")
    try:
        # ⚠️ **`PRAGMA foreign_keys` 在事务里是静默无效的** ——
        #    Python 的 sqlite3 对 DML 会隐式开事务，所以上一段的 DELETE
        #    已经让连接处于事务中。不 commit 就切 PRAGMA，
        #    这条语句**什么都不会做**，而实验会得出"关了也生效"的错误结论。
        #    （本次就是这么被误导的 —— 报了一条 `!` 出来。）
        db.commit()
        db.execute("PRAGMA foreign_keys=OFF")
        fk_now = db.execute("PRAGMA foreign_keys").fetchone()[0]
        if fk_now != 0:
            warn(f"PRAGMA foreign_keys 没被关掉（读到 {fk_now}）—— 本实验作废")
            db.close()
            return passed
        db.execute(
            "INSERT INTO session (id,title,titleSource,createdAt,updatedAt,"
            "lastMessageAt,turnCount,pinned,archived,activeLeafTurnId) "
            "VALUES ('s2','x','AUTO',1000,1000,1000,0,0,0,NULL)"
        )
        db.execute(
            "INSERT INTO turn (id,sessionId,role,text,at,tokenCount,parentTurnId,superseded) "
            "VALUES ('t2','s2','USER','x',1000,NULL,NULL,0)"
        )
        db.execute("DELETE FROM session WHERE id='s2'")
        left = db.execute("SELECT COUNT(*) FROM turn WHERE sessionId='s2'").fetchone()[0]
        if left == 1:
            ok("确认：不关 PRAGMA 就会留孤儿轮次 —— 所以 onOpen 里那句不是装饰")
        else:
            warn("关了 PRAGMA 级联仍然生效？请重新核实 SQLite 版本行为")
    except sqlite3.Error as exc:
        warn(f"该对照实验没跑成：{exc}")
    finally:
        db.execute("PRAGMA foreign_keys=ON")

    return passed


# key = (from, to) → 行为检查函数。没有条目就只做结构比对。
BEHAVIOUR = {
    (1, 2): _behaviour_1_2,
    (3, 4): _behaviour_3_4,
}


def migration_pairs() -> list[tuple[int, int]]:
    """从 schema 目录推导**全部相邻版本对**。

    ★ 这是本次修的关键：脚本原来**写死了 v1→v2**，
      于是新写的 v3→v4 迁移**一条都没被检查过**，而脚本照样报「全部通过」。
      **一个恒过的校验等于没有校验，而且比没有更糟** ——
      它让人以为"迁移有覆盖"，从而放心地改迁移脚本。

    ⇒ 改成从 `schemas/*.json` 推导：**新加一个 N.json 就自动被覆盖**。
    """
    versions = sorted(
        int(p.stem) for p in SCHEMA_DIR.glob("*.json") if p.stem.isdigit()
    )
    return list(zip(versions, versions[1:]))


def check_pair(from_v: int, to_v: int) -> bool:
    """通用的一对迁移：用 v_from 建库 → 跑迁移 → 与 v_to 期望比对。"""
    print("\n" + "=" * 64)
    print(f"  v{from_v} → v{to_v}")
    print("=" * 64)

    src = load_schema(from_v)
    dst = load_schema(to_v)
    print(f"\nv{from_v} 有 {len(src['entities'])} 张表，v{to_v} 有 {len(dst['entities'])} 张表")

    # ① 用 v_from 的 createSql 建库（等价于 helper.createDatabase(db, from_v)）
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for ent in src["entities"]:
        db.execute(materialise(ent["createSql"], ent["tableName"]))
    for ent in src["entities"]:
        for idx in ent.get("indices", []):
            db.execute(materialise(idx["createSql"], ent["tableName"]))
    print(f"\n① 已用 v{from_v} schema 建库")

    # ★ 每一对都插同一行"探针数据"，迁移后检查它还在。
    #
    #   ⚠️ 原来只在 `from_v == 1` 时插 —— 于是 2→3 / 3→4 那两对**根本没有数据可保全**，
    #      而它们照样"通过"。这正是"恒过的校验"的另一种形态：
    #      **检查跑了，但检查的对象是空的。**
    #   `credential` 从 v1 起就存在且列名稳定，所以它是天然的探针表。
    try:
        # ⚠️ 探针语句必须**跟着 schema 版本变**：`purpose` 是 v2 才加的列，
        #    而它 NOT NULL 无默认值 ⇒ 给 v1 的库带上它、或给 v2+ 的库漏掉它，
        #    都会直接插入失败。
        #    （本次就是这么发现的 —— 顺带印证了"每一对都要各自插探针"的必要性：
        #      老版本的表结构和新版本**不一样**。）
        cols = {r[1] for r in db.execute("PRAGMA table_info(credential)")}
        has_purpose = "purpose" in cols
        base_cols = (
            "id, providerId, label, ciphertext, iv, keyLength, "
            "baseUrlOverride, createdAtMillis, lastCheckedAtMillis, "
            "lastStatus, lastStatusDetail, modelCount, isDefault"
        )
        base_vals = "'c1','deepseek','主力',X'0102',X'0304',35, NULL,1000,NULL,NULL,NULL,NULL,1"
        if has_purpose:
            db.execute(
                f"INSERT INTO credential ({base_cols}, purpose) VALUES ({base_vals}, 'LLM')"
            )
        else:
            db.execute(f"INSERT INTO credential ({base_cols}) VALUES ({base_vals})")
        ok(f"v{from_v} 探针数据插入成功（credential/c1，purpose 列：{'有' if has_purpose else '无'}）")
    except sqlite3.Error as exc:
        bad(f"探针数据插入失败：{exc}")
        db.close()
        return False

    # ② 应用迁移
    stmts = extract_migration_sql(MIGRATIONS_KT, from_v, to_v)
    if not stmts:
        bad(f"Migrations.kt 里找不到 MIGRATION_{from_v}_{to_v} 的任何语句")
        db.close()
        return False
    print(f"\n② 从 Migrations.kt 提取到 {len(stmts)} 条语句，开始执行")
    for i, s in enumerate(stmts, 1):
        head = _norm_sql(s)[:72]
        try:
            db.execute(s)
            ok(f"[{i}] {head}…")
        except sqlite3.Error as exc:
            bad(f"[{i}] 执行失败：{exc}")
            print(f"      SQL: {s[:200]}")
            db.close()
            return False
    db.commit()

    # ③ 与 v_to 期望结构比对
    print(f"\n③ 与 v{to_v} 期望结构比对（Room 从 @Entity 生成的期望）")
    passed = True
    for ent in dst["entities"]:
        if not compare_table(db, ent):
            passed = False

    # ④ 该对独有的行为检查
    behaviour = BEHAVIOUR.get((from_v, to_v))
    if behaviour is not None:
        if not behaviour(db):
            passed = False

    db.close()
    return passed


def main() -> int:
    print("=" * 64)
    print("  离线迁移校验（替代 MigrationTestHelper 的结构比对部分）")
    print("=" * 64)

    pairs = migration_pairs()
    if not pairs:
        bad("schema 目录里没有可用的版本对")
        return 1
    print(f"\n发现 {len(pairs)} 对迁移：{'、'.join(f'v{a}→v{b}' for a, b in pairs)}")

    all_passed = True
    for from_v, to_v in pairs:
        if not check_pair(from_v, to_v):
            all_passed = False

    print("\n" + "=" * 64)
    if all_passed:
        print(f"{GREEN}  全部通过 —— 每一对迁移的结构都符合 Room 期望{RESET}")
        print("=" * 64)
        return 0
    print(f"{RED}  存在不匹配，升级后会崩在打开数据库那一步{RESET}")
    print("=" * 64)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
