package com.test.autostub;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

@SupportedAnnotationTypes("com.test.autostub.GenerateStub")
public class GenerateStubProcessor extends AbstractProcessor {

    private static final String COMMA_AND_SPACE= ", ";

    private static boolean DEBUG_PRINT = false;

    public static boolean isDEBUG_PRINT() {
        return DEBUG_PRINT;
    }

    private String prefix = "Stub";

    private String suffix = "";

    public GenerateStubProcessor() {
        super();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        // To be honest, this method was made just so that I could compile without a warning
        // telling me that annotations was not used.
        // -Wall -Werror is the way to go.
        printAllAnnotations(annotations);

        // Here, we are given a list of Element which were annotated by one of the annotations
        // contained in the annotations Set. As defined in the class annotation
        // @SupportedAnnotationTypes, we only have one annotation to process here, hence why we
        // do not bother to check by which annotation each element is annotated.
        for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(GenerateStub.class)) {
            GenerateStub generateStub = annotatedElement.getAnnotation(GenerateStub.class);
            Printer.debugPrint("Annotation @GenerateStub found on the element: " + annotatedElement.getSimpleName());

            // If the user has specified some prefix and/or suffix for the generated classes,
            // we take them into account. Otherwise, we just prefix the class name with "Stub"
            // and we do not add anything at the end of the class name.
            if (generateStub.prefix() != null && generateStub.prefix().length() > 0) {
                prefix = generateStub.prefix();
            }
            if (generateStub.suffix() != null && generateStub.suffix().length() > 0) {
                suffix = generateStub.suffix();
            }

            // TODO: extract all annotation parameters here and modify
            // the generating methods to take them into account


            // The @GenerateStub annotation takes an array of String as an parameter. Those
            // Strings contains the fully qualified name of an interface to generate a stub for.
            // However, if no argument is given, then the stub will extend or implement the annotated
            // class or interface.
            String[] toStub = generateStub.toStub();

            if (toStub == null || isInvalidParameterArray(toStub)) {
                Printer.debugPrint("Generating a stub for the annotated element: " + annotatedElement.getSimpleName());
                generateStubForElement(annotatedElement);
            }
            else {
                // For each string given as an argument
                for (String interfaceFullName : toStub) {
                    // If the string is a potential class or interface, we try to generate a stub for it
                    if (!isEmptyOrNullString(interfaceFullName)) {
                        Printer.debugPrint("Generating a stub for the element: " + interfaceFullName);
                        generateStubForInterface(interfaceFullName);
                    }
                    else {
                        Printer.debugPrint("Parameter was not a correct one: the String is empty or only contains whitespace characters.");
                    }
                }
            }

        }

        return true;
    }

    private boolean isEmptyOrNullString(String interfaceFullName) {
        Printer.debugPrint("interfaceFullName IS NULL: " + (interfaceFullName != null));
        return interfaceFullName == null || interfaceFullName.trim().length() <= 0;
    }

    private boolean isInvalidParameterArray(String[] toStub) {
        Printer.debugPrint("PARAMETER ARRAY LENGTH: " + toStub.length);
        return toStub.length == 0 || (toStub.length == 1 && isEmptyOrNullString(toStub[0]));
    }

    private void generateStubForInterface(String interfaceFullName) {
        // Here, we get the Element representing the interface to implement as a stub
        // from the processing environment.
        Element element = processingEnv.getElementUtils().getTypeElement(interfaceFullName);

        if (element == null) {
            // If element is null, that mean the given class could not be found in the
            // processing environment. We then throw a warning and do not do anything
            // else.
            processingEnv.getMessager().printMessage(Kind.WARNING, "Could not generate stub for interface " + interfaceFullName + ": class/interface not found");
        }
        else {
            generateStubForElement(element);
        }
    }

    private void generateStubForElement(Element element) {
        // We instantiate a Generator and give him a processing environment
        Generator generator = new Generator(processingEnv, prefix, suffix);

        // ...and we use it to generate the stub
        generator.generateStubClass(element);
    }

    void printDebug(Element element) {

        TypeElement classElement = (TypeElement) element;

        String qualifiedName = classElement.getQualifiedName().toString();

        Printer.debugPrint("QUALIFIED NAME: " + qualifiedName);

        String packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf("."));

        Printer.debugPrint("PACKAGE NAME: " + packageName);

        String fileName = packageName + ".Stub" + classElement.getSimpleName();

        Printer.debugPrint("FILE NAME: " + fileName);
    }

    void printAllAnnotations(Set<? extends TypeElement> annotations) {
        for (Object object : annotations) {
            TypeElement annotation = (TypeElement) object;
            Printer.debugPrint("Annotation " + annotation.getQualifiedName() + " will be processed");
        }

    }

    private class Generator {

        private static final String THROWS_KEYWORD = "throws";
        ProcessingEnvironment processingEnv = null;

        private String prefix = "Stub";

        private String suffix = "";

        public Generator(ProcessingEnvironment processingEnvironment, String prefix, String suffix) {
            this.processingEnv = processingEnvironment;
            this.prefix = prefix;
            this.suffix = suffix;
        }

        public String getMethodAsString(Element enclosed) {
            String methodAsString = null;

            // Safety checks :
            //  - If the element is null (should not happen), then
            //    we return null.
            //  - If the element is anything other than a method
            //    (that may happen if extending a class), then
            //    we just ignore it and return null.
            //  - Else, we go on with the generation.
            if (enclosed  != null && enclosed.getKind() == ElementKind.METHOD) {
                ExecutableType type = (ExecutableType) enclosed.asType();
                methodAsString = "";

                // So as to have unique names for our argument, I adopted a clever
                // naming convention : arg(i), where i is the position of the
                // argument in the method's signature.
                int nbArg = 0;

                // The method's modifiers. For example,
                // public static final
                Set<Modifier> modifiers = enclosed.getModifiers();

                for (Modifier modifier : modifiers) {
                    // Note that as we are providing IMPLEMENTATIONS of methods,
                    // we have to filter out the ABSTRACT modifier.
                    if (modifier.equals(Modifier.ABSTRACT)) {
                        continue;
                    }

                    // Also, since a interface is used to communicate with classes
                    // from the outside, we ignore the PROTECTED and PRIVATE modifiers
                    if (modifier.equals(Modifier.PROTECTED) || modifier.equals(Modifier.PRIVATE)) {
                        return null;
                    }

                    methodAsString += modifier.toString() + " ";
                }

                // The return type of the method
                methodAsString += getTypeMirrorName(type.getReturnType()) + " ";

                // The name of the method, as well as the opening bracket for parameters
                methodAsString += enclosed.getSimpleName() + "(";

                // The arguments of the method
                for (TypeMirror typeMirror : type.getParameterTypes()) {

                    // One of many ways to hand-write a comma-separated array/list/whatever
                    if (nbArg > 0) {
                        methodAsString += COMMA_AND_SPACE;
                    }

                    methodAsString += getTypeMirrorName(typeMirror) + " arg" + nbArg++;
                }

                // The closing bracket for the parameters
                methodAsString += ") ";

                String thrownExceptions = getThrownExceptionsAsString(type);

                if (thrownExceptions != null) {
                    methodAsString += thrownExceptions;
                }

                // The opening curly bracket for the method's bod
                methodAsString += " {\n";

                // If the method is returning something else than VOID, we should
                // fake the returned value too. Else, the compiler will get angry
                // at us when it will compile our class.
                if (!type.getReturnType().getKind().equals(TypeKind.VOID)) {
                    methodAsString += "    return ";
                    methodAsString += getDefaultValueForTypeKind(type.getReturnType().getKind());
                    methodAsString += ";\n";
                }

                // The closing curly bracket for the method's body
                methodAsString += "}\n";

                Printer.debugPrint(methodAsString);
            }

            return methodAsString;
        }

        public String getThrownExceptionsAsString(ExecutableType type) {
            String thrownExceptionsAsString = null;

            if (type.getThrownTypes() != null && type.getThrownTypes().size() > 0) {
                // The keyword is "throws"
                thrownExceptionsAsString = THROWS_KEYWORD;

                for (TypeMirror thrownException : type.getThrownTypes()) {
                    String exceptionQualifiedName = getTypeMirrorName(thrownException);

                    if (thrownExceptionsAsString.length() != THROWS_KEYWORD.length()) {
                        thrownExceptionsAsString += ", " + exceptionQualifiedName;
                    }
                    else {
                        thrownExceptionsAsString += " " + exceptionQualifiedName;
                    }
                }
            }

            return thrownExceptionsAsString;
        }

        public String getTypeMirrorName(TypeMirror typeMirror) {
            // All quotes in comment are from docs.oracle.com/javase/7/docs/api/javax/lang/model/type/*
            String name = typeMirror.getKind().toString();

            switch (typeMirror.getKind()) {
                case DECLARED:
                    // This could be any class: String, Integer, or even a user-defined one.
                    DeclaredType declaredType = ((DeclaredType) typeMirror);
                    name = getFullyQualifiedName(declaredType.asElement());

                    String typeArguments = getTypeArguments(declaredType);

                    if (typeArguments != null) {
                        name += typeArguments;
                    }

                    break;
                case VOID:
                    // Void. duh.
                    name = "void";
                    break;
                case INT:
                    name = "int";
                    break;
                case ARRAY:
                    // A array-type is easy enough to represent: just add [] at the end of the type
                    name = getTypeMirrorName( ((ArrayType)typeMirror).getComponentType() ) + "[]";
                    break;
                case BOOLEAN:
                    name = "boolean";
                    break;
                case BYTE:
                    name = "byte";
                    break;
                case CHAR:
                    name = "char";
                    break;
                case DOUBLE:
                    name = "double";
                    break;
                case ERROR:
                    // "Represents a class or interface type that cannot be properly modeled."
                    break;
                case EXECUTABLE:
                    break;
                case FLOAT:
                    name = "float";
                    break;
                case LONG:
                    name = "long";
                    break;
                case NONE:
                    break;
                case NULL:
                    // "Represents the null type. This is the type of the expression null."
                    break;
                case OTHER:
                    break;
                case PACKAGE:
                    break;
                case SHORT:
                    name = "short";
                    break;
                case TYPEVAR:
                    // "Represents a type variable. A type variable may be explicitly declared
                    // by a type parameter of a type, method, or constructor.
                    // A type variable may also be declared implicitly, as by the capture conversion
                    // of a wildcard type argument (see chapter 5 of The Java™ Language Specification)."
                    break;
                case WILDCARD:
                    // "Represents a wildcard type argument. Examples include:
                    // ?
                    // ? extends Number
                    // ? super T"
                    name = "?";
                    WildcardType wildcardType = (WildcardType) typeMirror;

                    if (wildcardType.getExtendsBound() != null) {
                        name += " extends " + getTypeMirrorName(wildcardType.getExtendsBound());
                    }
                    else {
                        if (wildcardType.getSuperBound() != null) {
                            name += " super " + getTypeMirrorName(wildcardType.getSuperBound());
                        }
                    }

                    break;
                default:
                    break;
            }

            return name;
        }

        public String getTypeArguments(DeclaredType declaredType) {
            String result = null;
            List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();

            // If the type has at least one type argument, process the list
            if (typeArguments != null && declaredType.getTypeArguments().size() > 0) {
                result = "<";
                boolean moreThanOneType = false;

                for (Object object : typeArguments) {
                    // We get the type name's as a String ...
                    TypeMirror typeMirror = (TypeMirror) object;
                    String typeName = getTypeMirrorName(typeMirror);

                    // ... and add it
                    if (moreThanOneType) {
                        // One of many ways to hand-write a comma-separated array/list/whatever
                        result = result.concat(COMMA_AND_SPACE);
                    }
                    else {
                        moreThanOneType = true;
                    }

                    result += typeName;
                }

                result += ">";
            }

            return result;
        }

        public String getDefaultValueForTypeKind(TypeKind typeKind) {
            String result = null;

            // Those values are taken directly from the Java Language Specification.
            // See docs.oracle.com/javase/specs/jls/se7/html/jls-4.html#jls-4.12.5
            switch(typeKind) {
                case BYTE:
                    result = "(byte)0";
                    break;
                case SHORT:
                    result = "(short)0";
                    break;
                case INT:
                    result = "0";
                    break;
                case LONG:
                    result = "0L";
                    break;
                case FLOAT:
                    result = "0.0f";
                    break;
                case DOUBLE:
                    result = "0.0d";
                    break;
                case CHAR:
                    result = "'\u0000'";
                    break;
                case BOOLEAN:
                    result = "false";
                    break;
                default:
                    result = "null";
                    break;
            }

            return result;
        }

        public String getClassInitializer(Element element) {
            String initializer = "";

            TypeElement classElement = (TypeElement) element;
            PackageElement packageElement =
                    (PackageElement) classElement.getEnclosingElement();

            // If the user has specified some prefix and/or suffix for the generated classes,
            // we take them into account. Otherwise, we just pefix the class name with "Stub"
            // and we do not add anything at the end of the class name.
            // Here, we generate the package name for the file ...
            initializer += "package " +  packageElement.getQualifiedName() + ";\n\n";
            // ... and the declaration of our class
            initializer += "public class " + prefix + element.getSimpleName() + suffix + " ";

            // Interfaces are IMPLEMENTED ...
            if (ElementKind.INTERFACE.equals(element.getKind())) {
                initializer += "implements ";
            }
            // ... while classes are EXTENDED
            if (ElementKind.CLASS.equals(element.getKind())) {
                initializer += "extends ";
            }

            // The fully qualified name of the elment we are writing a stub for
            initializer += getFullyQualifiedName(element);

            initializer += " {\n\n";

            Printer.debugPrint(initializer);

            return initializer;
        }

        public String getFullyQualifiedName(Element element) {
            String name = "";

           if (element != null) {
               name = processingEnv.getElementUtils().getPackageOf(element).getQualifiedName() + "." + element.getSimpleName();
           }

            return name;
        }

        public String getEndOfClassSequence() {
            String endingSequence = "\n}\n";

            Printer.debugPrint(endingSequence);

            return endingSequence;
        }

        void generateStubClass(Element element) {
            // The try...catch block is here to prevent any IOException from
            // interfering with the compiling process
            try {
                // We only want to be able to process class or interfaces.
                // Else, the behaviour of the processor is undefined.
                if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.INTERFACE) {
                    // This will print different version of the name of the class/interface
                    printDebug(element);

                    TypeElement classElement = (TypeElement) element;

                    // Here, we are building the fully qualified name of our new class which will
                    // implement the specified interface.

                    String qualifiedName = classElement.getQualifiedName().toString();
                    String fileName = qualifiedName.substring(0, qualifiedName.lastIndexOf(".")) + "." + prefix + classElement.getSimpleName() + suffix;

                    // We create a new source file in which we will write our newly (not yet) generated class
                    JavaFileObject jfo = processingEnv.getFiler().createSourceFile(fileName);
                    Printer.debugPrint("CREATED NEW FILE: " + fileName);

                    // We need to write in a file -> BufferedWriter
                    BufferedWriter bw = new BufferedWriter(jfo.openWriter());

                    // We first write the class initializer (the class name and the opening curly brace)
                    bw.append(getClassInitializer(element));

                    // We then iterate over everything contained in this class, and try to
                    // write a stub method for it.
                    for (Element enclosedElement : classElement.getEnclosedElements()) {
                        String methodAsString = getMethodAsString(enclosedElement);

                        // If the element is anything other than a method, null will be returned
                        if (methodAsString != null) {
                            bw.append(methodAsString);
                        }

                    }

                    // Finally, we write the closing curly brace of the class's body
                    bw.append(getEndOfClassSequence());

                    // To be safe, we flush the BufferedWriter, and then close it
                    bw.flush();
                    bw.close();
                }
                else {
                    // If the processor ends up here, it means the annotation was used on something
                    // other than a class or an interface. We log it, for debugging purposes only.
                    Printer.debugPrint("DEBUG: found element " + element.getSimpleName() + " of type " + element.getKind());
                }
            }
            catch(IOException ioe) {
                // Here is hoping this will never happen ...
                Printer.debugPrint("Error while writing new file: " + ioe);
            }
        }

    }

    private static class Printer {
        static void debugPrint(String... strings) {
            if (isDEBUG_PRINT()) {
                for (String string : strings) {
                    System.out.print(string);
                }
                System.out.println();
            }
        }
    }

}
