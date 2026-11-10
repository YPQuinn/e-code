package io.agentic.pplee.ecode.app;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 模块依赖规则：依赖只能向下，core 在最底层，同一层的模块互不依赖。
 * 规则按包名匹配。模块暂时没有类时，规则空转通过，所以用 allowEmptyShould。
 */
@AnalyzeClasses(packages = "io.agentic.pplee.ecode", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleDependencyRulesTest {

    private static final String CORE = "io.agentic.pplee.ecode.core..";
    private static final String MCP = "io.agentic.pplee.ecode.mcp..";
    private static final String MODEL_ANTHROPIC = "io.agentic.pplee.ecode.model.anthropic..";
    private static final String MODEL_OPENAI = "io.agentic.pplee.ecode.model.openai..";
    private static final String ACP = "io.agentic.pplee.ecode.acp..";
    private static final String APP = "io.agentic.pplee.ecode.app..";

    @ArchTest
    static final ArchRule coreDependsOnNoOtherModule = noClasses().that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(MCP, MODEL_ANTHROPIC, MODEL_OPENAI, ACP, APP)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule mcpDependsOnNoPeer = noClasses().that().resideInAPackage(MCP)
            .should().dependOnClassesThat().resideInAnyPackage(MODEL_ANTHROPIC, MODEL_OPENAI, ACP, APP)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule modelAnthropicDependsOnNoPeer = noClasses().that().resideInAPackage(MODEL_ANTHROPIC)
            .should().dependOnClassesThat().resideInAnyPackage(MCP, MODEL_OPENAI, ACP, APP)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule modelOpenaiDependsOnNoPeer = noClasses().that().resideInAPackage(MODEL_OPENAI)
            .should().dependOnClassesThat().resideInAnyPackage(MCP, MODEL_ANTHROPIC, ACP, APP)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule acpDependsOnNoPeer = noClasses().that().resideInAPackage(ACP)
            .should().dependOnClassesThat().resideInAnyPackage(MCP, MODEL_ANTHROPIC, MODEL_OPENAI, APP)
            .allowEmptyShould(true);
}
