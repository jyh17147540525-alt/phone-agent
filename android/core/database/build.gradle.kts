// ⚠️ 自动生成（gen_module_build_files.py）。如需长期定制，请移出生成列表。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.pocketagent.core.database"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // ⚠️ Robolectric 下也要能读到 schemas —— 否则 MigrationTestHelper
        //    找不到 1.json，报"can't find schema file"。
        unitTests.all { it.systemProperty("robolectric.logging", "stdout") }
    }

    // ⚠️ **这一块是 MigrationTestHelper 能工作的前提。**
    //    `room.schemaLocation` 只是让 Room 把 JSON **写**出去；
    //    测试要**读**它，还得把它挂进测试的 assets 路径。
    //    漏了这块的表现是运行时 `FileNotFoundException: Cannot find schema file` ——
    //    而那句报错完全不提示"你该配 sourceSets"。
    sourceSets {
        getByName("test").assets.srcDirs("${projectDir}/schemas")
        getByName("androidTest").assets.srcDirs("${projectDir}/schemas")
    }
}

// Room 的 schema 导出目录。PocketAgentDatabase 声明了 exportSchema = true，
// 而那个开关**只在给了这个路径之后才真正生效** —— 否则 Room 只会在构建日志里
// 打一条警告说"没有提供导出目录"，然后什么都不写。于是你以为有迁移依据，
// 实际上没有，直到某天要加字段才发现。
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    // ⚠️ 这两个必须是 api 而不是 implementation。
    //    PocketAgentDatabase **继承** RoomDatabase，且 withTransaction 来自 room-ktx ——
    //    两者都出现在本模块的公开契约里。用 implementation 的话，每个使用方
    //    （keymgmt / memory / agent …）都会撞上
    //    "Cannot access 'RoomDatabase' which is a supertype of ..."，
    //    而那句报错根本不会提示"该去上游模块改成 api"。
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)
    // ⚠️ 这一行曾经缺失，而缺了它**编译照样通过** ——
    //    @Database / @Dao 都是 abstract，没有处理器也能过编译。
    //    代价推到运行时：第一次建库直接抛
    //    "Cannot find implementation for PocketAgentDatabase"。
    //    这类"编译期无感、运行期必崩"的缺失，只有真机跑起来才会暴露。
    //
    //    根因在生成器：ksp_lines 被算出来却从未写进模板（死变量），
    //    所以从第一天起就没输出过这一行。现已修复，并用 --check 兜住复发。
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    // ⚠️ 迁移测试的唯一可靠手段。
    //    Room 只在**运行期**校验迁移（"Migration didn't properly handle ..."），
    //    编译期完全不管。没有这个依赖就只能靠人工核对 SQL，
    //    而人工核对漏掉一个 NOT NULL 的表现是：用户装上后崩在打开数据库那一步 ——
    //    他连界面都进不去，也就看不到任何提示。
    testImplementation(libs.androidx.room.testing)
    // `ApplicationProvider` 来自这里（MigrationTestHelper 需要 Context）
    testImplementation(libs.androidx.test.core)
}
