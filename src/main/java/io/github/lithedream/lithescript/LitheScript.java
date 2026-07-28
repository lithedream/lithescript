package io.github.lithedream.lithescript;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiled, reusable LitheScript program.
 *
 * <p>The initial language supports assignments, method-call chains and a final
 * return statement. Variables first assigned by the script are locals; other
 * identifiers are external values supplied at execution time.</p>
 */
public final class LitheScript {

    private final Command[] commands;
    private final ValueRef result;
    private final String[] externalNames;
    private final int localCount;

    private LitheScript(Command[] commands, ValueRef result, String[] externalNames, int localCount) {
        this.commands = commands;
        this.result = result;
        this.externalNames = externalNames;
        this.localCount = localCount;
    }

    public static LitheScript compile(String source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        return new Compiler(source).compile();
    }

    public static Object eval(String source, Map<String, ?> externals) {
        return compile(source).execute(externals);
    }

    public String[] getExternalNames() {
        return externalNames.clone();
    }

    public Object execute(Map<String, ?> externals) {
        if (externals == null) {
            throw new NullPointerException("externals");
        }
        Object[] values = new Object[externalNames.length];
        for (int i = 0; i < externalNames.length; i++) {
            String name = externalNames[i];
            if (!externals.containsKey(name)) {
                throw new IllegalArgumentException("Missing external value: " + name);
            }
            values[i] = externals.get(name);
        }
        return executeArguments(values);
    }

    public Object executeArguments(Object[] externals) {
        if (externals == null) {
            throw new NullPointerException("externals");
        }
        if (externals.length != externalNames.length) {
            throw new IllegalArgumentException(
                    "Expected " + externalNames.length + " external values, got " + externals.length);
        }

        Object[] locals = new Object[localCount];
        for (Command command : commands) {
            command.execute(externals, locals);
        }
        return result == null ? null : result.read(externals, locals);
    }

    private interface ValueRef {
        Object read(Object[] externals, Object[] locals);
    }

    private static final class ExternalRef implements ValueRef {
        private final int index;

        private ExternalRef(int index) {
            this.index = index;
        }

        @Override
        public Object read(Object[] externals, Object[] locals) {
            return externals[index];
        }
    }

    private static final class LocalRef implements ValueRef {
        private final int index;

        private LocalRef(int index) {
            this.index = index;
        }

        @Override
        public Object read(Object[] externals, Object[] locals) {
            return locals[index];
        }
    }

    private static final class ConstantRef implements ValueRef {
        private final Object value;

        private ConstantRef(Object value) {
            this.value = value;
        }

        @Override
        public Object read(Object[] externals, Object[] locals) {
            return value;
        }
    }

    private interface Command {
        void execute(Object[] externals, Object[] locals);
    }

    private static final class AssignCommand implements Command {
        private final int destination;
        private final ValueRef source;

        private AssignCommand(int destination, ValueRef source) {
            this.destination = destination;
            this.source = source;
        }

        @Override
        public void execute(Object[] externals, Object[] locals) {
            locals[destination] = source.read(externals, locals);
        }
    }

    private static final class CallCommand implements Command {
        private final int destination;
        private final ValueRef receiver;
        private final String methodName;
        private final ValueRef[] arguments;
        private final int sourceOffset;

        private CallCommand(int destination, ValueRef receiver, String methodName,
                ValueRef[] arguments, int sourceOffset) {
            this.destination = destination;
            this.receiver = receiver;
            this.methodName = methodName;
            this.arguments = arguments;
            this.sourceOffset = sourceOffset;
        }

        @Override
        public void execute(Object[] externals, Object[] locals) {
            Object target = receiver.read(externals, locals);
            if (target == null) {
                throw new ExecutionException("Cannot invoke " + methodName + " on null", sourceOffset);
            }

            Object[] values = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                values[i] = arguments[i].read(externals, locals);
            }

            Method method = resolveMethod(target.getClass(), methodName, values, sourceOffset);
            try {
                Object value = method.invoke(target, values);
                if (destination >= 0) {
                    locals[destination] = value;
                }
            } catch (IllegalAccessException e) {
                throw new ExecutionException("Cannot access method " + methodName, sourceOffset, e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new ExecutionException("Method " + methodName + " failed", sourceOffset, cause);
            }
        }
    }

    private static Method resolveMethod(Class<?> type, String name, Object[] arguments, int sourceOffset) {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean ambiguous = false;

        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals(name) || candidate.getParameterTypes().length != arguments.length) {
                continue;
            }
            int score = compatibilityScore(candidate.getParameterTypes(), arguments);
            if (score < 0) {
                continue;
            }
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
                ambiguous = false;
            } else if (score == bestScore) {
                ambiguous = true;
            }
        }

        if (best == null) {
            throw new ExecutionException(
                    "No compatible method " + type.getName() + "." + name + "/" + arguments.length,
                    sourceOffset);
        }
        if (ambiguous) {
            throw new ExecutionException(
                    "Ambiguous method " + type.getName() + "." + name + "/" + arguments.length,
                    sourceOffset);
        }
        return best;
    }

    private static int compatibilityScore(Class<?>[] parameterTypes, Object[] arguments) {
        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = wrap(parameterTypes[i]);
            Object argument = arguments[i];
            if (argument == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return -1;
                }
                score += 100;
            } else if (parameterType == argument.getClass()) {
                // Exact match.
            } else if (parameterType.isAssignableFrom(argument.getClass())) {
                score += inheritanceDistance(argument.getClass(), parameterType);
            } else {
                return -1;
            }
        }
        return score;
    }

    private static int inheritanceDistance(Class<?> actual, Class<?> expected) {
        if (expected.isInterface()) {
            return 1;
        }
        int distance = 0;
        for (Class<?> current = actual; current != null; current = current.getSuperclass()) {
            if (current == expected) {
                return distance;
            }
            distance++;
        }
        return distance + 10;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    public static final class CompilationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int sourceOffset;

        private CompilationException(String message, int sourceOffset) {
            super(message + " at offset " + sourceOffset);
            this.sourceOffset = sourceOffset;
        }

        public int getSourceOffset() {
            return sourceOffset;
        }
    }

    public static final class ExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int sourceOffset;

        private ExecutionException(String message, int sourceOffset) {
            super(message + " at offset " + sourceOffset);
            this.sourceOffset = sourceOffset;
        }

        private ExecutionException(String message, int sourceOffset, Throwable cause) {
            super(message + " at offset " + sourceOffset, cause);
            this.sourceOffset = sourceOffset;
        }

        public int getSourceOffset() {
            return sourceOffset;
        }
    }

    private static final class Compiler {
        private final Parser parser;
        private final List<Command> commands = new ArrayList<Command>();
        private final Map<String, Integer> externals = new LinkedHashMap<String, Integer>();
        private final Map<String, Integer> locals = new LinkedHashMap<String, Integer>();
        private int nextLocal;
        private ValueRef result;
        private boolean returned;

        private Compiler(String source) {
            this.parser = new Parser(source);
        }

        private LitheScript compile() {
            while (!parser.isEnd()) {
                compileStatement();
                if (returned && !parser.isEnd()) {
                    throw parser.error("No statement is allowed after return");
                }
            }
            return new LitheScript(
                    commands.toArray(new Command[commands.size()]),
                    result,
                    externals.keySet().toArray(new String[externals.size()]),
                    nextLocal);
        }

        private void compileStatement() {
            if (parser.consumeKeyword("return")) {
                result = compileExpression();
                parser.expect(';');
                returned = true;
                return;
            }

            int mark = parser.position();
            String possibleTarget = parser.tryIdentifier();
            if (possibleTarget != null && parser.consume('=')) {
                int destination = local(possibleTarget);
                ValueRef source = compileExpression();
                parser.expect(';');
                commands.add(new AssignCommand(destination, source));
                return;
            }
            parser.position(mark);

            ValueRef value = compileExpression();
            parser.expect(';');
            if (value instanceof LocalRef && ((LocalRef) value).index == nextLocal - 1) {
                // The final temporary is intentionally ignored.
            }
        }

        private ValueRef compileExpression() {
            ValueRef value = compilePrimary();
            while (parser.consume('.')) {
                int offset = parser.position();
                String methodName = parser.identifier();
                parser.expect('(');
                List<ValueRef> arguments = new ArrayList<ValueRef>();
                if (!parser.consume(')')) {
                    do {
                        arguments.add(compileArgument());
                    } while (parser.consume(','));
                    parser.expect(')');
                }
                int temporary = temporary();
                commands.add(new CallCommand(
                        temporary,
                        value,
                        methodName,
                        arguments.toArray(new ValueRef[arguments.size()]),
                        offset));
                value = new LocalRef(temporary);
            }
            return value;
        }

        private ValueRef compileArgument() {
            ValueRef argument = compilePrimary();
            if (parser.peek('.')) {
                throw parser.error("Nested method calls in arguments are not supported; assign the value first");
            }
            return argument;
        }

        private ValueRef compilePrimary() {
            if (parser.consumeKeyword("null")) return new ConstantRef(null);
            if (parser.consumeKeyword("true")) return new ConstantRef(Boolean.TRUE);
            if (parser.consumeKeyword("false")) return new ConstantRef(Boolean.FALSE);
            if (parser.peek('"')) return new ConstantRef(parser.stringLiteral());
            if (parser.peekDigit()) return new ConstantRef(Integer.valueOf(parser.integerLiteral()));

            String name = parser.identifier();
            Integer local = locals.get(name);
            if (local != null) {
                return new LocalRef(local.intValue());
            }
            Integer external = externals.get(name);
            if (external == null) {
                external = Integer.valueOf(externals.size());
                externals.put(name, external);
            }
            return new ExternalRef(external.intValue());
        }

        private int local(String name) {
            if (externals.containsKey(name)) {
                throw parser.error("External variable cannot later become a local: " + name);
            }
            Integer slot = locals.get(name);
            if (slot == null) {
                slot = Integer.valueOf(nextLocal++);
                locals.put(name, slot);
            }
            return slot.intValue();
        }

        private int temporary() {
            return nextLocal++;
        }
    }

    private static final class Parser {
        private final String source;
        private int position;

        private Parser(String source) {
            this.source = source;
        }

        private int position() {
            return position;
        }

        private void position(int position) {
            this.position = position;
        }

        private boolean isEnd() {
            skipIgnored();
            return position >= source.length();
        }

        private boolean peek(char expected) {
            skipIgnored();
            return position < source.length() && source.charAt(position) == expected;
        }

        private boolean peekDigit() {
            skipIgnored();
            return position < source.length() && Character.isDigit(source.charAt(position));
        }

        private boolean consume(char expected) {
            if (!peek(expected)) {
                return false;
            }
            position++;
            return true;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean consumeKeyword(String keyword) {
            skipIgnored();
            int end = position + keyword.length();
            if (end > source.length() || !source.regionMatches(position, keyword, 0, keyword.length())) {
                return false;
            }
            if (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
                return false;
            }
            position = end;
            return true;
        }

        private String tryIdentifier() {
            skipIgnored();
            if (position >= source.length() || !Character.isJavaIdentifierStart(source.charAt(position))) {
                return null;
            }
            int start = position++;
            while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
                position++;
            }
            return source.substring(start, position);
        }

        private String identifier() {
            String value = tryIdentifier();
            if (value == null) {
                throw error("Expected identifier");
            }
            return value;
        }

        private String stringLiteral() {
            skipIgnored();
            int start = position;
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < source.length()) {
                char ch = source.charAt(position++);
                if (ch == '"') {
                    return value.toString();
                }
                if (ch == '\\') {
                    if (position >= source.length()) {
                        break;
                    }
                    char escaped = source.charAt(position++);
                    if (escaped == 'n') value.append('\n');
                    else if (escaped == 'r') value.append('\r');
                    else if (escaped == 't') value.append('\t');
                    else if (escaped == '"' || escaped == '\\') value.append(escaped);
                    else throw new CompilationException("Unsupported escape: \\" + escaped, position - 1);
                } else {
                    value.append(ch);
                }
            }
            throw new CompilationException("Unterminated string literal", start);
        }

        private int integerLiteral() {
            skipIgnored();
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) {
                position++;
            }
            try {
                return Integer.parseInt(source.substring(start, position));
            } catch (NumberFormatException e) {
                throw new CompilationException("Invalid integer literal", start);
            }
        }

        private void skipIgnored() {
            while (position < source.length()) {
                char ch = source.charAt(position);
                if (Character.isWhitespace(ch)) {
                    position++;
                } else if (ch == '/' && position + 1 < source.length() && source.charAt(position + 1) == '/') {
                    position += 2;
                    while (position < source.length() && source.charAt(position) != '\n') {
                        position++;
                    }
                } else {
                    return;
                }
            }
        }

        private CompilationException error(String message) {
            return new CompilationException(message, position);
        }
    }
}
