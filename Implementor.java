package info.kgeorgiy.ja.petrova.implementor;

import info.kgeorgiy.java.advanced.implementor.Impler;
import info.kgeorgiy.java.advanced.implementor.ImplerException;
import info.kgeorgiy.java.advanced.implementor.JarImpler;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;

/**  Implements classes.
 * Generates class implementation by {@link Class type token} of class or interface.
 * Сan also get .jar file containing the compiled implementing class.
 * */
public class Implementor implements Impler, JarImpler {

    /** Line break. */
    private static final String NEW_LINE = System.lineSeparator();

    /** Default constructor. */
    public Implementor() {}

    @Override
    public void implement(Class<?> token, Path root) throws ImplerException {
        tokenValidation(token);

        String rawCode = generateImplementation(token);
        String code = modifyCode(rawCode);

        Path pathFile = getFile(root, token, "Impl.java");
        createDirectories(pathFile);
        writeCodeToFile(pathFile, code);
    }

    /** Validates the {@code token}.
     * Checks that the {@code token} is a valid class whose implementation can be created.
     *
     * @param token type token to create implementation for.
     * @throws ImplerException if
     * token is null or primitive type or array or {@link java.lang.Enum} or {@link java.lang.Record}
     * or contains a private or a final modifier
     * or contains only private constructors.
     * */
    private void tokenValidation(Class<?> token) throws ImplerException {
        if (token == null) {
            throw new ImplerException("Invalid token. Token is null");
        }
        if (token.isArray()) {
            throw new ImplerException("Invalid token. Token must not be array: " + token.getCanonicalName());
        } else if (token.isPrimitive()) {
            throw new ImplerException("Invalid token. Token must not be primitive type: " + token.getCanonicalName());
        } else if (Modifier.isPrivate(token.getModifiers())) {
            throw new ImplerException("Invalid token. Token must not be private: " + token.getCanonicalName());
        } else if (Modifier.isFinal(token.getModifiers())) {
            throw new ImplerException("Invalid token. Token must not be final: " + token.getCanonicalName());
        } else if (token.isAssignableFrom(java.lang.Enum.class) ||
                token.isAssignableFrom(java.lang.Record.class)) {
            throw new ImplerException("Invalid token. Token must not be java.lang.Enum.class or java.lang.Record.class: " + token.getCanonicalName());
        } else if (!token.isInterface() && allConstructorsIsPrivate(token.getDeclaredConstructors())) {
            throw new ImplerException("Invalid token. Token must not contains only private constructors: " + token.getCanonicalName());
        }
    }

    /** Checks that the class contains only private constructors.
     *
     * @param constructors constructors of the class.
     * @return is all {@code constructors} contains a private modifier.
     * */
    private boolean allConstructorsIsPrivate(Constructor<?>[] constructors) {
        boolean flag = true;
        for (var constructor : constructors) {
            if (!Modifier.isPrivate(constructor.getModifiers())) {
                flag = false;
                break;
            }
        }
        return flag;
    }

    /** Gets the path from the {@code root} to the {@code clazz}.
     * A {@code suffix} is added to the class name, and the absolute path from the root to the class with this suffix is taken.
     *
     * @param root root directory.
     * @param clazz type token of class.
     * @param suffix string to add to class-name.
     * @throws ImplerException
     * if resolved string or final path can not be converted to absolute path.
     * @return absolute path of {@code root} resolved package of {@code clazz}, name of {@code clazz} and some {@code suffix}.
     * */
    private Path getFile(Path root, Class<?> clazz, String suffix) throws ImplerException {
        String packageName = clazz.getPackageName().replace(".", File.separator);
        String className = clazz.getSimpleName();
        try {
            return root.resolve(packageName + File.separator + className + suffix).toAbsolutePath();
        } catch (InvalidPathException | IOError | SecurityException e) {
            throw new ImplerException("Can not create path to file", e);
        }
    }

    /** Creates directories for {@code pathFile}.
     *
     * @param pathFile directory to create.
     * @throws ImplerException
     * if some exception was caught during directory creation process.
     * */
    private void createDirectories(Path pathFile) throws ImplerException {
        try {
            if (pathFile.getParent() != null) {
                Files.createDirectories(pathFile.getParent());
            }
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            throw new ImplerException("Can not create directories to file. Path to File: " + pathFile, e);
        }
    }

    /** Converts Unicode.
     *
     * @param code string of code.
     * @return string where every character is escaped.
     * */
    private String modifyCode(String code) {
        return IntStream.range(0, code.length())
                .mapToObj(i -> new StringBuilder()
                        .append(String.format("\\u%04x", (int) code.charAt(i))))
                .collect(Collectors.joining());
    }

    /** Write {@code code} to file given by {@code pathFile}.
     *
     * @param pathFile path of file in which {@code code} will be written.
     * @param code string to write.
     * @throws ImplerException
     * if some exception was caught during creation writer or writing process.
     * */
    private void writeCodeToFile(Path pathFile, String code) throws ImplerException {
        try (BufferedWriter writer = Files.newBufferedWriter(pathFile)) {
            writer.write(code);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | SecurityException e) {
            throw new ImplerException("Error with writing to the file", e);
        }
    }

    /** Takes a set of two arrays.
     * Creates a set of all elements of the type {@code T} from the class:
     * the set contains both private, protected, public elements and elements received from the parent.
     *
     * @param <T> type of elements ({@link java.lang.reflect.Constructor} or {@link java.lang.reflect.Method})
     * @param elements array of elements received from the parent.
     * @param declaredElements array of elements defined in class.
     * @return set of {@code declaredElements} and {@code elements}
     * */
    private <T> Set<T> getSet(T[] declaredElements, T[] elements) {
        Set<T> set = new HashSet<>(List.of(declaredElements));
        set.addAll(List.of(elements));
        return set;
    }

    /** Determines what access rights a constructor or method has (public or private) by its {@code modifiers}.
     *
     * @param modifiers integer representing the set of declared modifiers.
     * @return string with access right.
     * */
    private String printModifiers(int modifiers) {
        if (Modifier.isPublic(modifiers)) {
            return "public ";
        } else if (Modifier.isProtected(modifiers)) {
            return "protected ";
        } else {
            return "";
        }
    }

    /** Get canonical name of {@code type}.
     *
     * @param type type token.
     * @return string with canonical name of {@code type}.
     * */
    private String printType(Class<?> type) {
        return type.getCanonicalName() + " ";
    }

    /** Returns string containing the default value of the {@code type}.
     *
     * @param type type token.
     * @return default value of {@code type} or empty string.
     * */
    private String printDefaultValue(Class<?> type) {
        if (type == boolean.class) {
            return "false";
        } else if (type == short.class || type == int.class || type == long.class ||
                type == float.class || type == double.class ||
                type== char.class || type == byte.class) {
            return "0";
        } else if (type == void.class) {
            return "";
        } else {
            return "null";
        }
    }

    /** If the enumeration has not ended, the entry is separated by a comma, otherwise empty string.
     *
     * @param i current index.
     * @param length size of enumeration.
     * @return comma if {@code i < length - 1}.
     * */
    private String printComma(int i, int length) {
        return i < length - 1 ? ", " : "";
    }

    /** Return a string with sequential writing, separated by commas, of the names of the parameters of a method or constructor
     *  and, if {@code typeIsNeeded}, their types.
     *
     * @param parameters array of parameters that need to be written into the code.
     * @param typeIsNeeded does need to record parameter types.
     * @return string of code with sequentially listed parameters.
     * */
    private String printParameters(Parameter[] parameters, boolean typeIsNeeded) {
        return IntStream.range(0, parameters.length)
                .mapToObj(i -> new StringBuilder()
                        .append(typeIsNeeded ? printType(parameters[i].getType()) : "")
                        .append(parameters[i].getName())
                        .append(printComma(i, parameters.length)))
                .collect(Collectors.joining());
    }

    /** Return sequential comma-separated {@code constructor} exceptions.
     * If the constructor does not throw exceptions, it returns an empty string.
     *
     * @param constructor constructor which exceptions must be written.
     * @return string of code with sequentially listed constructors.
     * */
    private String printExceptions(Constructor<?> constructor) {
        Class<?>[] exceptions = constructor.getExceptionTypes();
        if (exceptions.length > 0) {
            return "throws " +
                    IntStream.range(0, exceptions.length)
                            .mapToObj(i -> new StringBuilder()
                                    .append(exceptions[i].getCanonicalName())
                                    .append(printComma(i, exceptions.length)))
                            .collect(Collectors.joining());
        } else {
            return "";
        }
    }

    /** If {@code isConstructor} creates a string with a call to the super constructor,
     * otherwise creates a string with the return default value of the method.
     *
     * @param <T> type of element ({@link java.lang.reflect.Constructor} or {@link java.lang.reflect.Method}).
     * @param isConstructor is {@code T} a constructor.
     * @param element specific method or constructor for which to create a default call.
     * @return part of the code inside the method or constructor.
     * */
    private <T extends Executable> String printSuperConstructorOrDefaultValue(boolean isConstructor, T element) {
        if (isConstructor) {
            return "    super(" +
                    printParameters(element.getParameters(), false) + ")";
        } else {
            return "    return " +
                    printDefaultValue(((Method) element).getReturnType());
        }
    }

    /** Adds to the code an implementation of class methods if {@code flagFunction}? else - constructors.
     * Creates a string containing a formatted default implementation.
     *
     * @param <T> type of elements ({@link java.lang.reflect.Constructor} or {@link java.lang.reflect.Method}).
     * @param elements set of methods or constructors which must be implemented.
     * @param implClassName class name with suffixes Impl if {@code T} is {@link java.lang.reflect.Constructor}, else -- null.
     * @param function modifier function by the value of which elements will be filtered ({@link Modifier#isAbstract(int)} or {@link Modifier#isPrivate(int)}).
     * @param flagFunction should elements with this modifier be inserted
     * @return string with a sequential formatted default implementation of methods or constructors.
     * @see #addMethods(Class)
     * @see #addConstructors(Class)
     * */
    private <T extends Executable> String addMethodsOrConstructors(Set<T> elements,
                                                                   String implClassName,
                                                                   boolean flagFunction,
                                                                   Function<Integer, Boolean> function) {
        return elements.stream()
                .filter(element -> function.apply(element.getModifiers()).equals(flagFunction))
                .map(element -> new StringBuilder()
                        .append(printModifiers(element.getModifiers()))
                        .append((implClassName == null) ? printType(((Method) element).getReturnType()) : "")
                        .append((implClassName != null) ? implClassName : element.getName())
                        .append("(")
                        .append(printParameters(element.getParameters(), true))
                        .append(") ")
                        .append((implClassName != null) ? printExceptions((Constructor<?>) element) : "")
                        .append("{").append(NEW_LINE)
                        .append(printSuperConstructorOrDefaultValue((implClassName != null), element))
                        .append(";").append(NEW_LINE)
                        .append("}").append(NEW_LINE))
                .collect(Collectors.joining());
    }

    /** Add an implementation of class methods to the code.
     * Implements all abstract methods of {@code clazz}.
     * The implementation is the return of the default value of the function type.
     *
     * @param clazz type token class to create implementation for.
     * @return string with a sequential formatted default implementation of methods.
     * @see #addMethodsOrConstructors(Set, String, boolean, Function)
     * */
    private String addMethods(Class<?> clazz) {
        Set<Method> methods = getSet(clazz.getDeclaredMethods(), clazz.getMethods());
        return addMethodsOrConstructors(methods, null, true, Modifier::isAbstract);
    }

    /** Add an implementation of class constructors to the code.
     * Implements all declared constructors of {@code clazz} except private ones.
     * The implementation is a call to the super constructor.
     *
     * @param clazz type token class to create implementation for.
     * @return string with a sequential formatted default implementation of constructors.
     * @see #addMethodsOrConstructors(Set, String, boolean, Function)
     * */
    private String addConstructors(Class<?> clazz) {
        Set<Constructor<?>> constructors = new HashSet<>(List.of(clazz.getDeclaredConstructors()));
        String implClassName = clazz.getSimpleName() + "Impl";
        return addMethodsOrConstructors(constructors, implClassName, false, Modifier::isPrivate);
    }

    /** Generates an implementation of a class or interface.
     * Creates a string containing formatted code of the declaration and implementation of {@code clazz}.
     *
     * @param clazz type token to create implementation for.
     * @return string of code.
     * */
    private String generateImplementation(Class<?> clazz) {
        StringBuilder code = new StringBuilder();

        if (!clazz.getPackageName().isEmpty()) {
            code.append("package ").append(clazz.getPackageName()).append(";").append(NEW_LINE).append(NEW_LINE);
        }

        code.append("public class ").append(clazz.getSimpleName()).append("Impl").append(" ");
        if (clazz.isInterface()) {
            code.append("implements ");
        } else {
            code.append("extends ");
        }
        code.append(clazz.getCanonicalName());

        code.append(" {").append(NEW_LINE);
        if (!clazz.isInterface()) {
            code.append(addConstructors(clazz));
        }
        code.append(addMethods(clazz));
        code.append("}").append(NEW_LINE);

        return code.toString();
    }

    /** Template of the arguments under which the method {@link #main(String[])} should be called. */
    private static final String MESSAGE = "The arguments should be:" + NEW_LINE +
            "class_name path_to_class --- to generate an implementation of the class/interface" + NEW_LINE +
            "-jar class_name jar_file_name.jar --- to generate a .jar file with the implementation of the corresponding class";

    /**Prints error information.
     * Prints the reason, the arguments under which it occurred,
     * and the template of the arguments under which the method {@link #main(String[])} should be called.
     *
     * @param error error message.
     * @param args argument cmd line.
     * @param implerMessage error message of type {@link ImplerException}
     * */
    private static void printError(String error, String[] args, String implerMessage) {
        System.out.println(error + NEW_LINE);
        if (implerMessage != null) {
            System.out.println(implerMessage + NEW_LINE);
        }
        if (args != null) {
            System.out.println("Your arguments: " + Arrays.toString(args) + NEW_LINE);
        }
        System.out.println(MESSAGE);
    }

    /**The main method, the call of which creates an implementation of some class or interface,
     * with the possibility of packaging in jar-file.
     * Accepts two types of possible parameters: 1. class_name path_to_class; 2. -jar class_name jar_file_name.
     * In first case creates calls the method {@link #implement(Class, Path)}, in second -- {@link #implementJar(Class, Path)}.
     *
     * @param args argument cmd line.
     * @see #implement(Class, Path)
     * @see #implementJar(Class, Path)
     * */
    public static void main(String[] args) {
        if (args == null || args.length < 2 || args.length > 3) {
            printError("Incorrect number of arguments", null, null);
            return;
        } else if (args[0] == null || args[1] == null ||
                (args.length == 3 && args[2] == null)) {
            printError("Argument is null", null, null);
            return;
        }

        Implementor implementor = new Implementor();
        try {
            if (args[0].equals("-jar")) {
                implementor.implementJar(Class.forName(args[1]), Path.of(args[2]));
            } else {
                implementor.implement(Class.forName(args[0]), Path.of(args[1]));
            }
        } catch (ClassNotFoundException e) {
            printError("Incorrect class-name", args, null);
        } catch (InvalidPathException e) {
            printError("Incorrect path", args, null);
        } catch (ImplerException e) {
            printError("Error with implementation", args, e.getMessage());
        }
    }


    @Override
    public void implementJar(Class<?> token, Path jarFilePath) throws ImplerException {
        Path directory = jarFilePath;
        if (jarFilePath.getParent() != null) {
            directory = jarFilePath.getParent().toAbsolutePath();
        }

        implement(token, directory);
        compile(token, directory);
        createJar(jarFilePath, token, directory);
    }

    /** Compile .java file.
     * Compile the implementation of {@code token} in specified {@code directory}.
     *
     * @param token type token.
     * @param directory directory where the implementation is located in the form .java file.
     * @throws ImplerException if can not get path to {@code token} or can not compile.
     * */
    private void compile(Class<?> token, Path directory) throws ImplerException {
        Path pathJavaFile = getFile(directory, token, "Impl.java");
        String classPath = getClassPath(token);
        String[] args = {"-cp", classPath, pathJavaFile.toString()};

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int exitCode = compiler.run(null, null, null, args);
        if (exitCode != 0) {
            throw new ImplerException("Can not compile .java");
        }
    }

    /** Get path of class {@code token}.
     *
     * @param token type token.
     * @return string of token path.
     * @throws ImplerException if can not get ProtectionDomain of this class or converts URL to URI.
     * */
    private String getClassPath(Class<?> token) throws ImplerException {
        try {
            return Path.of(token.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException | SecurityException e) {
            throw new ImplerException("Can not get path of class", e);
        }
    }

    /** Creates a jar-file.
     * Creates a jar-file at the specified path {@code jarFilePath}
     * and places there, along the path from the package and name, a compiled class that implements {@code token}.
     *
     * @param jarFilePath path for jar-file.
     * @param token type token of class whose implementation needs to be packaged.
     * @param directory path where the .class file that needs to be packed is located.
     * @throws ImplerException if can not write entry or copy .class file.
     * */
    private void createJar(Path jarFilePath, Class<?> token, Path directory) throws ImplerException {
        Path pathClassFile = getFile(directory, token, "Impl.class");

        try (JarOutputStream writer = new JarOutputStream(Files.newOutputStream(jarFilePath))) {
            writer.putNextEntry(new ZipEntry(
                    token.getPackageName().replace(".", "/") + "/" + token.getSimpleName() + "Impl.class"));
            Files.copy(pathClassFile, writer);
            writer.closeEntry();
        } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException | IOException e) {
            throw new ImplerException("Can not create jar", e);
        }
    }
}

