package io.github.lithedream.lithescript;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LitheScriptTest {

    @Test
    void executesTheInitialTargetSyntax() {
        LitheScript script = LitheScript.compile(
                "v = a.getB().getC(param);\n" +
                "v = a.call(v);\n" +
                "return v;");

        Map<String, Object> externals = new HashMap<String, Object>();
        externals.put("a", new A());
        externals.put("param", "moon");

        assertArrayEquals(new String[] { "a", "param" }, script.getExternalNames());
        assertEquals("called:c:moon", script.execute(externals));
    }

    @Test
    void supportsConstantsAndExactOverloadResolution() {
        LitheScript script = LitheScript.compile("return target.pick(42);");

        Map<String, Object> externals = new HashMap<String, Object>();
        externals.put("target", new Overloaded());

        assertEquals("integer", script.execute(externals));
    }

    @Test
    void rejectsNestedCallsInsideArguments() {
        LitheScript.CompilationException error = assertThrows(
                LitheScript.CompilationException.class,
                () -> LitheScript.compile("return a.call(b.get());"));

        assertEquals(true, error.getMessage().contains("assign the value first"));
    }

    @Test
    void rejectsStatementsAfterReturn() {
        assertThrows(
                LitheScript.CompilationException.class,
                () -> LitheScript.compile("return a; b = a;"));
    }

    public static final class A {
        public B getB() {
            return new B();
        }

        public String call(String value) {
            return "called:" + value;
        }
    }

    public static final class B {
        public String getC(String parameter) {
            return "c:" + parameter;
        }
    }

    public static final class Overloaded {
        public String pick(Number value) {
            return "number";
        }

        public String pick(Integer value) {
            return "integer";
        }
    }
}
