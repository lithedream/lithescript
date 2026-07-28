# LitheScript

A tiny dependency-free Java library for compiling limited Java-like invocation scripts into reusable execution plans.

## Why

LitheScript is intended for small sequential workflows that should be easier to store and change than Java code, without embedding a complete programming language or runtime.

## Current syntax

The first implementation supports:

- external values supplied by name;
- local assignment without type declarations;
- instance method calls and call chains;
- identifier, string, integer, boolean and `null` arguments;
- a final `return` statement;
- `//` comments.

```java
v = a.getB().getC(param);
v = a.call(v);
return v;
```

Nested calls inside argument lists are deliberately excluded for now. Assign the intermediate value first instead:

```java
value = b.getC(param);
result = a.call(value);
return result;
```

## Usage

```java
LitheScript script = LitheScript.compile(
        "v = a.getB().getC(param);" +
        "v = a.call(v);" +
        "return v;");

Map<String, Object> externals = new HashMap<>();
externals.put("a", a);
externals.put("param", param);

Object result = script.execute(externals);
```

Compilation produces an immutable sequence of commands using indexed runtime slots. Syntax and variable names are resolved before execution; Java methods are selected from the runtime receiver and argument types.

## Status

LitheScript is at an experimental pre-1.0 stage. Syntax and internal representation may change while the minimal language is refined.

## Author

- **lithedream**

## License

MIT
