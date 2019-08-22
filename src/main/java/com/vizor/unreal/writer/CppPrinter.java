/*
 * Copyright 2018 Vizor Games LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.vizor.unreal.writer;

import com.vizor.unreal.tree.CppArgument;
import com.vizor.unreal.tree.CppClass;
import com.vizor.unreal.tree.CppDelegate;
import com.vizor.unreal.tree.CppEnum;
import com.vizor.unreal.tree.CppEnumElement;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppFunction;
import com.vizor.unreal.tree.CppJavaDoc;
import com.vizor.unreal.tree.CppNamespace;
import com.vizor.unreal.tree.CppRecord;
import com.vizor.unreal.tree.CppRecordContainer;
import com.vizor.unreal.tree.CppStruct;
import com.vizor.unreal.tree.CppType;
import com.vizor.unreal.tree.preprocessor.CppInclude;
import com.vizor.unreal.tree.preprocessor.CppMacroIf;
import com.vizor.unreal.tree.preprocessor.CppPragma;
import com.vizor.unreal.writer.annotation.DummyDecoratorWriter;
import com.vizor.unreal.writer.annotation.UEDecoratorWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import static com.vizor.unreal.tree.CppRecord.Residence.Header;
import static java.lang.String.valueOf;
import static java.text.MessageFormat.format;
import static java.util.Arrays.asList;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

@SuppressWarnings("UnusedReturnValue")
public class CppPrinter implements AutoCloseable
{
    private static final Supplier<Collection<String>> fileHeader = () -> asList(
        "",
        "This file has been generated by the Cornerstone file generator.",
        "",
        "PLEASE, DO NOT EDIT IT MANUALLY",
        ""
    );

    // Common constants
    private static final String headerExtension = ".h";
    private static final String codeExtension = ".cpp";
    private static final String commaSeparator = ", ";

    // Automatically added into an every Cpp file
    private static final CppPragma pragmaOnce = new CppPragma(Header, "once");

    private final ContentWriter header = new ContentWriter();
    private final ContentWriter codeFile = new ContentWriter();
    private ContentWriter current = header;

    private final Path absPathToFile;
    private final DummyDecoratorWriter decoratorWriter;

    public CppPrinter(Path absPathToFile, String apiName)
    {
        this.absPathToFile = absPathToFile;
        this.decoratorWriter = new UEDecoratorWriter(apiName);

        // Write header (copyright, etc.) to both .h and .cpp
        asList(header, codeFile).forEach(cw -> {
            current = cw;

            newLine();
            fileHeader.get().forEach(this::writeInlineComment);
            newLine();
        });

        // Switch to header, and write include guard
        pragmaOnce.accept(this);
        newLine();
    }

    @Override
    public final void close()
    {
        final String absoluteHeaderPath = absPathToFile + headerExtension;
        final String absoluteCodePath = absPathToFile + codeExtension;

        header.writeToFile(absoluteHeaderPath);
        codeFile.writeToFile(absoluteCodePath);
    }

    public final CppPrinter writeInlineComment(String comment)
    {
        return write("// ").writeLine(comment);
    }

    public final CppPrinter writeLine(String line)
    {
        return write(line).newLine();
    }

    public final CppPrinter write(String line)
    {
        current.write(line);
        return this;
    }

    public final CppPrinter newLine()
    {
        current.newLine();
        return this;
    }

    private CppPrinter removeLine()
    {
        current.removeLine();
        return this;
    }

    private CppPrinter incTab()
    {
        current.incTabs();
        return this;
    }

    private CppPrinter decTab()
    {
        current.decTabs();
        return this;
    }

    private CppPrinter backspace(int numBackspaces)
    {
        current.backspace(numBackspaces);
        return this;
    }

    private CppPrinter header()
    {
        current = header;
        return this;
    }

    private CppPrinter code()
    {
        current = codeFile;
        return this;
    }

    public void visit(CppJavaDoc jd)
    {
        final List<String> lines = jd.getLines();
        if (!lines.isEmpty())
        {
            writeLine("/**");
            lines.forEach(line -> write(" * ").writeLine(line));
            writeLine(" */");
        }
    }

    public void visit(CppField field)
    {
        field.javaDoc.accept(this);

        if (field.isAnnotationsEnabled())
            decoratorWriter.writeAnnotations(this, field);

        field.getType().accept(this).write(" ");
        write(field.getName()).write(";");
    }

    public void visit(CppType type)
    {
        // Write namespaces (if have some)
        type.getNamespaces().forEach(ns -> write(ns.getName()).write("::"));

        // Write type name
        write(type.getName());

        // If a type is kinda generic - write its arguments
        if (type.isGeneric())
        {
            write("<");
            type.getGenericParams().forEach(argument -> argument.accept(this).write(commaSeparator));
            backspace(commaSeparator.length()).write(">");
        }

        write(type.getPassage().getSymbols());
    }

    public void visit(CppInclude i)
    {
        final List<ContentWriter> writers = new ArrayList<>(2);
        switch (i.getResidence())
        {
            case Header:
                writers.add(header);
                break;
            case Cpp:
                writers.add(codeFile);
                break;
            case Split:
                writers.add(header);
                writers.add(codeFile);
                break;
        }

        for (ContentWriter w : writers)
        {
            w.write("#include ");
            w.writeLine(format(i.getFormatter(), i.getInclude()));
        }
    }

    private void writeClassOrStruct(CppStruct struct, CppType superType)
    {
        final CppType structType = struct.getType();

        // write javadoc
        struct.javaDoc.accept(this);

        if (struct.isAnnotationsEnabled())
            decoratorWriter.writeAnnotations(this, struct);

        write(structType.getKind().name().toLowerCase()).write(" ");

        // Add API if has some
        decoratorWriter.writeApi(this);

        structType.accept(this);
        if (nonNull(superType))
        {
            write(" : public ");
            superType.accept(this);
        }

        newLine();
        writeLine("{").incTab();

        if (struct.isAnnotationsEnabled())
            decoratorWriter.writeGeneratedBody(this, struct);

        final List<CppType> friendTypes = struct.getFriendDeclarations();
        if (!friendTypes.isEmpty())
        {
            friendTypes.forEach(f -> write("friend ").write(f.getKind().getCppKindName()).writeLine(";"));
            newLine();
        }

        if (!struct.getFields().isEmpty())
        {
            newLine().decTab().writeLine("public:").incTab();
            writeInlineComment("Conduits and GRPC stub");
            struct.getFields().forEach(f -> f.accept(this).newLine().newLine());
        }
    }

    private void switchWriterByResidence(CppRecord cppRecord)
    {
        if (cppRecord.getResidence() == CppRecord.Residence.Cpp)
            code();
        else
            header();
    }

    public void visit(final CppStruct struct)
    {
        switchWriterByResidence(struct);
        writeClassOrStruct(struct, null);

        switchWriterByResidence(struct);

        if (!struct.getFields().isEmpty())
            removeLine();

        decTab().writeLine("};");
    }

    public void visit(final CppClass clazz)
    {
        switchWriterByResidence(clazz);
        writeClassOrStruct(clazz, clazz.getSuperType());

        newLine();

        // Write to code file if not writing in the header
        final ContentWriter previous = current;
        if (clazz.getResidence() != Header)
            code().newLine().header();
        current = previous;

        writeInlineComment("Methods");
        clazz.getMethods().forEach(m -> m.accept(this));

        switchWriterByResidence(clazz);

        removeLine();
        decTab().writeLine("};");
    }

    public void visit(CppEnum cppEnum)
    {
        switchWriterByResidence(cppEnum);

        cppEnum.getJavaDoc().accept(this);
        decoratorWriter.writeAnnotations(this, cppEnum);
        write("enum class ");

        // blueprint type enums must extend uint8
        cppEnum.getType().accept(this).writeLine(" : uint8");

        writeLine("{").incTab();

        cppEnum.getCppEnumElements().stream()
                .sorted(comparingInt(CppEnumElement::getValue))
                .forEach(e -> write(e.getName()).write(" = ").write(valueOf(e.getValue())).writeLine(","));

        decTab().writeLine("};");
    }

    public void visit(CppFunction f)
    {
        switchWriterByResidence(f);

        writeMethodHeader(f, false);

        if (f.getResidence() == CppRecord.Residence.Split)
        {
            write(";"); // end of function declaration.
            newLine().newLine();

            code();
            writeMethodHeader(f, true);
        }

        newLine().writeLine("{").incTab();
        f.getBody().forEach(this::writeLine);
        decTab().write("}");

        newLine().newLine();
    }

    private void writeMethodHeader(CppFunction f, boolean isCppDefinition)
    {
        if (nonNull(f.getGenericParams()))
        {
            if (f.isAnnotationsEnabled())
                throw new RuntimeException("A generic method '" + f.getName() + "' mustn't have any annotations");

            write("template <");
            f.getGenericParams().forEach(p -> p.accept(this).write(commaSeparator));

            if (!f.getGenericParams().isEmpty())
                backspace(commaSeparator.length());
            writeLine(">");
        }
        if (f.isAnnotationsEnabled() && !isCppDefinition) // Don't write annotations in .cpp
        {
            if (nonNull(f.getGenericParams()))
                throw new RuntimeException("A generic method '" + f.getName() + "' mustn't have any annotations");

            decoratorWriter.writeAnnotations(this, f);
        }

        if (f.getInlineModifier() != CppFunction.InlineModifier.NoInline && !isCppDefinition)
            write(f.getInlineModifier().name).write(" ");

        // Method must be virtual and we mustn't write in the c++ file ('virtual' keyword ain't allowed in .cpp)
        if (f.isVirtual && !isCppDefinition)
            write("virtual ");

        // Same with 'static'
        if (f.isStatic && !isCppDefinition)
            writeLine("static ");

        f.getReturnType().accept(this).write(" ");

        if (isCppDefinition)
        {
            final CppClass declaringClass = f.getDeclaringItem();
            requireNonNull(declaringClass, () -> "While printing c++ definition, the expected declaring class of '" +
                    f.getName() + "' wasn't expected to be null (it seems like the method doesn't belong to any class)");

            declaringClass.getType().accept(this).write("::");
        }

        write(f.getName()).write("(");
        if (!f.getArguments().isEmpty())
        {
            f.getArguments().forEach(a -> a.accept(this).write(commaSeparator));
            backspace(commaSeparator.length());
        }
        write(")");

        if (f.isConst)
            write(" const");

        if (f.isOverride && !isCppDefinition)
            write(" override");
    }

    public void accept(CppArgument cppArgument)
    {
        final CppType type = cppArgument.getType();

        if (type.getPassage() == CppType.Passage.ByRef)
            write("const ");

        type.accept(this);

        write(" ").write(cppArgument.getName());
    }

    public void visit(CppNamespace namespace)
    {
        switchWriterByResidence(namespace);
        write("namespace ");

        final String name = namespace.getName();
        if (!name.isEmpty())
            writeLine(name);
        else
            writeInlineComment("$anonymous");

        writeLine("{").incTab();
        namespace.getResidents().forEach(r -> r.accept(this));
        removeLine();
        decTab().write("}").writeInlineComment("end namespace '" + name + "'");


        // Return back to header
        header();
    }

    public void visit(final CppDelegate delegate)
    {
        // First we declare a dynamic event
        if (delegate.isDynamic())
        {
            write("DECLARE_DYNAMIC_MULTICAST_DELEGATE");
        }
        else
        {
            write("DECLARE_MULTICAST_DELEGATE");
        }

        final String tense = delegate.getTense();
        if (!tense.isEmpty())
            write("_").write(tense);

        write("(");
        delegate.getType().accept(this);

        for (CppArgument a : delegate.getArguments())
        {
            write(", ");
            final CppType argType = a.getType();

            // Blueprint event dispatchers now only support const references.
            // If an input type is struct - should add a 'const' modifier
            if (argType.getPassage() == CppType.Passage.ByRef)
                write("const ");

            argType.accept(this);

            if (delegate.isDynamic())
            {
                write(", ");
                write(a.getName());
            }
        }
        write(");");
    }

    public void visit (final CppPragma pragma)
    {
        switchWriterByResidence(pragma);

        write("#pragma ").write(pragma.getBody());

        final String pragmaComment = pragma.getComment();

        if (!pragmaComment.isEmpty())
            writeInlineComment(pragmaComment);
        else
            newLine();
    }

    public void visit (final CppRecordContainer container)
    {
        switchWriterByResidence(container);
    }

    public void visit (final CppMacroIf cppMacroIf)
    {
        switchWriterByResidence(cppMacroIf);

        final CppMacroIf.Branch ifBranch = cppMacroIf.getIfBranch();
        final CppMacroIf.Branch elseBranch = cppMacroIf.getElseBranch();

        final List<CppMacroIf.Branch> elseIfBranches = cppMacroIf.getElseIfBranches();

        newLine();
        write("#if ").writeLine(ifBranch.getCondition());

        incTab();
        ifBranch.getRecords().forEach(i -> i.accept(this));
        decTab();

        // Write else if branches
        for (final CppMacroIf.Branch eb : elseIfBranches)
        {
            write("#elif ").writeLine(eb.getCondition());

            incTab();
            eb.getRecords().forEach(i -> i.accept(this));
            decTab();
        }

        if (!elseBranch.isEmpty())
        {
            writeLine("#else");

            incTab();
            elseBranch.getRecords().forEach(i -> i.accept(this));
            decTab();
        }

        writeLine("#endif");
        newLine();
    }
}
