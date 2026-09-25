package com.loom;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 架构约束测试。
 *
 * <p><b>这是本项目里最重要的一组测试。</b>它把「分层规则」从口头约定变成<b>会失败的测试</b>。
 *
 * <p>为什么单人开发尤其需要它：半年后你自己就会图省事，在 Controller 里直接调 Mapper， 或者让 {@code common} 反过来引用某个业务实体。没有第二个人会拦住你
 * —— 除了这个测试。 它用机器替代了 code review。
 *
 * <p>规则被破坏时构建失败，且失败信息会直接告诉你哪条规则、哪个类、哪一行。
 *
 * <p>调整规则时请同步更新 {@code docs/decisions.md} —— 规则背后的「为什么」比规则本身更容易被遗忘。
 */
@AnalyzeClasses(packages = "com.loom", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** 业务模块 —— 每一个都是未来可能的微服务边界。 */
    private static final String[] BUSINESS_MODULES = {
        "com.loom.auth..",
        "com.loom.system..",
        "com.loom.connection..",
        "com.loom.plugin..",
        "com.loom.workflow.."
    };

    // ------------------------------------------------------------------
    // 模块边界
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule 共享内核不应依赖任何业务模块 =
            noClasses()
                    .that()
                    .resideInAPackage("com.loom.common..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(BUSINESS_MODULES)
                    .because(
                            "common 未来要抽成独立的 loom-common jar，被所有服务依赖；"
                                    + "它一旦反向依赖业务模块，所有服务就会被强行绑在一起");

    @ArchTest
    static void 业务模块之间不应互相依赖(JavaClasses classes) {
        for (String module : BUSINESS_MODULES) {
            String[] otherModules =
                    Arrays.stream(BUSINESS_MODULES)
                            .filter(m -> !m.equals(module))
                            .toArray(String[]::new);
            noClasses()
                    .that()
                    .resideInAPackage(module)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(otherModules)
                    .because(module + " 必须保持自包含，否则未来无法独立拆分为微服务；" + "跨模块协作请通过接口或事件，不要直接引用对方的类")
                    .check(classes);
        }
    }

    // ------------------------------------------------------------------
    // 分层规则
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule 控制器不应直接依赖持久层 =
            noClasses()
                    .that()
                    .resideInAPackage("..controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..mapper..", "com.baomidou.mybatisplus..")
                    .because(
                            "Controller 只负责协议转换，业务逻辑与数据访问必须下沉到 Service；"
                                    + "Controller 直接写 SQL 会让业务规则无法复用、无法测试");

    @ArchTest
    static final ArchRule 实体不应出现在控制器的公开接口上 =
            noClasses()
                    .that()
                    .resideInAPackage("..controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..entity..")
                    .because("Controller 应使用 DTO 而非持久化实体，" + "否则数据库字段一改就直接破坏 API 契约");

    // ------------------------------------------------------------------
    // 编码约束
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule 不应使用标准输出 =
            noClasses()
                    .should()
                    .accessField(System.class, "out")
                    .because("日志必须走 SLF4J —— 否则不会有 TraceId、不会落文件、" + "无法按级别过滤，线上排查时这些输出等于不存在");

    @ArchTest
    static final ArchRule 不应使用标准错误输出 =
            noClasses().should().accessField(System.class, "err").because("错误日志同样必须走 SLF4J，理由同上");

    @ArchTest
    static final ArchRule 不应使用过时的日期类型 =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("java.util.Date", "java.util.Calendar", "java.sql.Date")
                    .because(
                            "统一使用 java.time（LocalDateTime / Instant / OffsetDateTime）—— "
                                    + "Date 可变、时区语义含糊，是 bug 高发区");

    @ArchTest
    static final ArchRule 不应使用字段注入 =
            noFields()
                    .should()
                    .beAnnotatedWith(Autowired.class)
                    .because("字段注入隐藏依赖关系、妨碍不可变设计、且脱离 Spring 容器就无法实例化；" + "统一用构造器注入");
}
