/*
 * Copyright (C) 2013-2019 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import java.util.ArrayList;

import javax.lang.model.element.Modifier;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCArrayTypeTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Builder.ObtainVia;
import lombok.ConfigurationKeys;
import lombok.Singular;
import lombok.ToString;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.HandlerPriority;
import lombok.core.handlers.HandlerUtil;
import lombok.core.handlers.InclusionExclusionUtils.Included;
import lombok.experimental.NonFinal;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.handlers.HandleConstructor.SkipIfConstructorExists;
import lombok.javac.handlers.JavacSingularsRecipes.JavacSingularizer;
import lombok.javac.handlers.JavacSingularsRecipes.SingularData;
import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;
import static lombok.javac.Javac.*;
import static lombok.javac.JavacTreeMaker.TypeTag.*;

@ProviderFor(JavacAnnotationHandler.class)
@HandlerPriority(-1024) //-2^10; to ensure we've picked up @FieldDefault's changes (-2048) but @Value hasn't removed itself yet (-512), so that we can error on presence of it on the builder classes.
public class HandleBuilder extends JavacAnnotationHandler<Builder> {
	private HandleConstructor handleConstructor = new HandleConstructor();
	
	private static final boolean toBoolean(Object expr, boolean defaultValue) {
		if (expr == null) return defaultValue;
		if (expr instanceof JCLiteral) return ((Integer) ((JCLiteral) expr).value) != 0;
		return ((Boolean) expr).booleanValue();
	}
	
	static class BuilderFieldData {
		List<JCAnnotation> annotations;
		JCExpression type;
		Name rawName;
		Name name;
		Name builderFieldName;
		Name nameOfDefaultProvider;
		Name nameOfSetFlag;
		SingularData singularData;
		ObtainVia obtainVia;
		JavacNode obtainViaNode;
		JavacNode originalFieldNode;
		
		java.util.List<JavacNode> createdFields = new ArrayList<JavacNode>();
	}
	
	@Override public void handle(AnnotationValues<Builder> annotation, JCAnnotation ast, JavacNode annotationNode) {
		handleFlagUsage(annotationNode, ConfigurationKeys.BUILDER_FLAG_USAGE, "@Builder");
		
		Builder builderInstance = annotation.getInstance();
		AccessLevel accessForOuters = builderInstance.access();
		if (accessForOuters == null) accessForOuters = AccessLevel.PUBLIC;
		if (accessForOuters == AccessLevel.NONE) {
			annotationNode.addError("AccessLevel.NONE is not valid here");
			accessForOuters = AccessLevel.PUBLIC;
		}
		AccessLevel accessForInners = accessForOuters == AccessLevel.PROTECTED ? AccessLevel.PUBLIC : accessForOuters;
		
		// These exist just to support the 'old' lombok.experimental.Builder, which had these properties. lombok.Builder no longer has them.
		boolean fluent = toBoolean(annotation.getActualExpression("fluent"), true);
		boolean chain = toBoolean(annotation.getActualExpression("chain"), true);
		
		String builderMethodName = builderInstance.builderMethodName();
		String buildMethodName = builderInstance.buildMethodName();
		String builderClassName = builderInstance.builderClassName();
		String toBuilderMethodName = "toBuilder";
		boolean toBuilder = builderInstance.toBuilder();
		java.util.List<Name> typeArgsForToBuilder = null;
		
		if (builderMethodName == null) builderMethodName = "builder";
		if (buildMethodName == null) buildMethodName = "build";
		if (builderClassName == null) builderClassName = "";
		
		boolean generateBuilderMethod;
		if (builderMethodName.isEmpty()) {
			generateBuilderMethod = false;
		} else if (!checkName("builderMethodName", builderMethodName, annotationNode)) {
			return;
		} else {
			generateBuilderMethod = true;
		}
		
		if (!checkName("buildMethodName", buildMethodName, annotationNode)) return;
		if (!builderClassName.isEmpty()) {
			if (!checkName("builderClassName", builderClassName, annotationNode)) return;
		}
		
		deleteAnnotationIfNeccessary(annotationNode, Builder.class, "lombok.experimental.Builder");
		
		JavacNode parent = annotationNode.up();
		
		java.util.List<BuilderFieldData> builderFields = new ArrayList<BuilderFieldData>();
		JCExpression returnType;
		List<JCTypeParameter> typeParams = List.nil();
		List<JCExpression> thrownExceptions = List.nil();
		Name nameOfBuilderMethod;
		JavacNode tdParent;
		
		JavacNode fillParametersFrom = parent.get() instanceof JCMethodDecl ? parent : null;
		boolean addCleaning = false;
		boolean isStatic = true;
		
		ArrayList<JavacNode> nonFinalNonDefaultedFields = null;
		
		if (builderClassName.isEmpty()) builderClassName = annotationNode.getAst().readConfiguration(ConfigurationKeys.BUILDER_CLASS_NAME);
		if (builderClassName == null || builderClassName.isEmpty()) builderClassName = "*Builder";
		boolean replaceNameInBuilderClassName = builderClassName.contains("*");
		
		if (parent.get() instanceof JCClassDecl) {
			tdParent = parent;
			JCClassDecl td = (JCClassDecl) tdParent.get();
			ListBuffer<JavacNode> allFields = new ListBuffer<JavacNode>();
			boolean valuePresent = (hasAnnotation(lombok.Value.class, parent) || hasAnnotation("lombok.experimental.Value", parent));
			for (JavacNode fieldNode : HandleConstructor.findAllFields(tdParent, true)) {
				JCVariableDecl fd = (JCVariableDecl) fieldNode.get();
				JavacNode isDefault = findAnnotation(Builder.Default.class, fieldNode, false);
				boolean isFinal = (fd.mods.flags & Flags.FINAL) != 0 || (valuePresent && !hasAnnotation(NonFinal.class, fieldNode));
				
				BuilderFieldData bfd = new BuilderFieldData();
				bfd.rawName = fd.name;
				bfd.name = removePrefixFromField(fieldNode);
				bfd.builderFieldName = bfd.name;
				bfd.annotations = findCopyableAnnotations(fieldNode);
				bfd.type = fd.vartype;
				bfd.singularData = getSingularData(fieldNode);
				bfd.originalFieldNode = fieldNode;
				
				if (bfd.singularData != null && isDefault != null) {
					isDefault.addError("@Builder.Default and @Singular cannot be mixed.");
					findAnnotation(Builder.Default.class, fieldNode, true);
					isDefault = null;
				}
				
				if (fd.init == null && isDefault != null) {
					isDefault.addWarning("@Builder.Default requires an initializing expression (' = something;').");
					findAnnotation(Builder.Default.class, fieldNode, true);
					isDefault = null;
				}
				
				if (fd.init != null && isDefault == null) {
					if (isFinal) continue;
					if (nonFinalNonDefaultedFields == null) nonFinalNonDefaultedFields = new ArrayList<JavacNode>();
					nonFinalNonDefaultedFields.add(fieldNode);
				}
				
				if (isDefault != null) {
					bfd.nameOfDefaultProvider = parent.toName("$default$" + bfd.name);
					bfd.nameOfSetFlag = parent.toName(bfd.name + "$set");
					bfd.builderFieldName = parent.toName(bfd.name + "$value");
					JCMethodDecl md = generateDefaultProvider(bfd.nameOfDefaultProvider, fieldNode, td.typarams);
					recursiveSetGeneratedBy(md, ast, annotationNode.getContext());
					if (md != null) injectMethod(tdParent, md);
				}
				addObtainVia(bfd, fieldNode);
				builderFields.add(bfd);
				allFields.append(fieldNode);
			}
			
			handleConstructor.generateConstructor(tdParent, AccessLevel.PACKAGE, List.<JCAnnotation>nil(), allFields.toList(), false, null, SkipIfConstructorExists.I_AM_BUILDER, annotationNode);
			
			returnType = namePlusTypeParamsToTypeReference(tdParent.getTreeMaker(), td.name, td.typarams);
			typeParams = td.typarams;
			thrownExceptions = List.nil();
			nameOfBuilderMethod = null;
			if (replaceNameInBuilderClassName) builderClassName = builderClassName.replace("*", td.name.toString());
			replaceNameInBuilderClassName = false;
		} else if (fillParametersFrom != null && fillParametersFrom.getName().toString().equals("<init>")) {
			JCMethodDecl jmd = (JCMethodDecl) fillParametersFrom.get();
			if (!jmd.typarams.isEmpty()) {
				annotationNode.addError("@Builder is not supported on constructors with constructor type parameters.");
				return;
			}
			
			tdParent = parent.up();
			JCClassDecl td = (JCClassDecl) tdParent.get();
			returnType = namePlusTypeParamsToTypeReference(tdParent.getTreeMaker(), td.name, td.typarams);
			typeParams = td.typarams;
			thrownExceptions = jmd.thrown;
			nameOfBuilderMethod = null;
			if (replaceNameInBuilderClassName) builderClassName = builderClassName.replace("*", td.name.toString());
			replaceNameInBuilderClassName = false;
		} else if (fillParametersFrom != null) {
			tdParent = parent.up();
			JCClassDecl td = (JCClassDecl) tdParent.get();
			JCMethodDecl jmd = (JCMethodDecl) fillParametersFrom.get();
			isStatic = (jmd.mods.flags & Flags.STATIC) != 0;
			JCExpression fullReturnType = jmd.restype;
			returnType = fullReturnType;
			typeParams = jmd.typarams;
			thrownExceptions = jmd.thrown;
			nameOfBuilderMethod = jmd.name;
			if (returnType instanceof JCTypeApply) {
				returnType = cloneType(tdParent.getTreeMaker(), returnType, ast, annotationNode.getContext());
			}
			if (replaceNameInBuilderClassName) {
				String replStr = null;
				if (returnType instanceof JCFieldAccess) {
					replStr = ((JCFieldAccess) returnType).name.toString();
				} else if (returnType instanceof JCIdent) {
					Name n = ((JCIdent) returnType).name;
					
					for (JCTypeParameter tp : typeParams) {
						if (tp.name.equals(n)) {
							annotationNode.addError("@Builder requires specifying 'builderClassName' if used on methods with a type parameter as return type.");
							return;
						}
					}
					replStr = n.toString();
				} else if (returnType instanceof JCPrimitiveTypeTree) {
					replStr = returnType.toString();
					if (Character.isLowerCase(replStr.charAt(0))) {
						replStr = Character.toTitleCase(replStr.charAt(0)) + replStr.substring(1);
					}
				} else if (returnType instanceof JCTypeApply) {
					JCExpression clazz = ((JCTypeApply) returnType).clazz;
					if (clazz instanceof JCFieldAccess) {
						replStr = ((JCFieldAccess) clazz).name.toString();
					} else if (clazz instanceof JCIdent) {
						replStr = ((JCIdent) clazz).name.toString();
					}
				}
				
				if (replStr == null || replStr.isEmpty()) {
					// This shouldn't happen.
					System.err.println("Lombok bug ID#20140614-1651: javac HandleBuilder: return type to name conversion failed: " + returnType.getClass());
					replStr = td.name.toString();
				}
				builderClassName = builderClassName.replace("*", replStr);
				replaceNameInBuilderClassName = false;
			}
			if (replaceNameInBuilderClassName) builderClassName = builderClassName.replace("*", td.name.toString());
			if (toBuilder) {
				final String TO_BUILDER_NOT_SUPPORTED = "@Builder(toBuilder=true) is only supported if you return your own type.";
				if (returnType instanceof JCArrayTypeTree) {
					annotationNode.addError(TO_BUILDER_NOT_SUPPORTED);
					return;
				}
				
				Name simpleName;
				String pkg;
				List<JCExpression> tpOnRet = List.nil();
				
				if (fullReturnType instanceof JCTypeApply) {
					tpOnRet = ((JCTypeApply) fullReturnType).arguments;
				}
				
				JCExpression namingType = returnType;
				if (returnType instanceof JCTypeApply) namingType = ((JCTypeApply) returnType).clazz;
				
				if (namingType instanceof JCIdent) {
					simpleName = ((JCIdent) namingType).name;
					pkg = null;
				} else if (namingType instanceof JCFieldAccess) {
					JCFieldAccess jcfa = (JCFieldAccess) namingType;
					simpleName = jcfa.name;
					pkg = unpack(jcfa.selected);
					if (pkg.startsWith("ERR:")) {
						String err = pkg.substring(4, pkg.indexOf("__ERR__"));
						annotationNode.addError(err);
						return;
					}
				} else {
					annotationNode.addError("Expected a (parameterized) type here instead of a " + namingType.getClass().getName());
					return;
				}
				
				if (pkg != null && !parent.getPackageDeclaration().equals(pkg)) {
					annotationNode.addError(TO_BUILDER_NOT_SUPPORTED);
					return;
				}
				
				if (!tdParent.getName().contentEquals(simpleName)) {
					annotationNode.addError(TO_BUILDER_NOT_SUPPORTED);
					return;
				}
				
				List<JCTypeParameter> tpOnMethod = jmd.typarams;
				List<JCTypeParameter> tpOnType = ((JCClassDecl) tdParent.get()).typarams;
				typeArgsForToBuilder = new ArrayList<Name>();
				
				for (JCTypeParameter tp : tpOnMethod) {
					int pos = -1;
					int idx = -1;
					for (JCExpression tOnRet : tpOnRet) {
						idx++;
						if (!(tOnRet instanceof JCIdent)) continue;
						if (((JCIdent) tOnRet).name != tp.name) continue;
						pos = idx;
					}
					
					if (pos == -1 || tpOnType.size() <= pos) {
						annotationNode.addError("@Builder(toBuilder=true) requires that each type parameter on the static method is part of the typeargs of the return value. Type parameter " + tp.name + " is not part of the return type.");
						return;
					}
					typeArgsForToBuilder.add(tpOnType.get(pos).name);
				}
			}
		} else {
			annotationNode.addError("@Builder is only supported on types, constructors, and methods.");
			return;
		}
		
		if (fillParametersFrom != null) {
			for (JavacNode param : fillParametersFrom.down()) {
				if (param.getKind() != Kind.ARGUMENT) continue;
				BuilderFieldData bfd = new BuilderFieldData();
				
				JCVariableDecl raw = (JCVariableDecl) param.get();
				bfd.name = raw.name;
				bfd.builderFieldName = bfd.name;
				bfd.rawName = raw.name;
				bfd.annotations = findCopyableAnnotations(param);
				bfd.type = raw.vartype;
				bfd.singularData = getSingularData(param);
				bfd.originalFieldNode = param;
				addObtainVia(bfd, param);
				builderFields.add(bfd);
			}
		}
		
		JavacNode builderType = findInnerClass(tdParent, builderClassName);
		if (builderType == null) {
			builderType = makeBuilderClass(isStatic, annotationNode, tdParent, builderClassName, typeParams, ast, accessForOuters);
			recursiveSetGeneratedBy(builderType.get(), ast, annotationNode.getContext());
		} else {
			JCClassDecl builderTypeDeclaration = (JCClassDecl) builderType.get();
			if (isStatic && !builderTypeDeclaration.getModifiers().getFlags().contains(Modifier.STATIC)) {
				annotationNode.addError("Existing Builder must be a static inner class.");
				return;
			} else if (!isStatic && builderTypeDeclaration.getModifiers().getFlags().contains(Modifier.STATIC)) {
				annotationNode.addError("Existing Builder must be a non-static inner class.");
				return;
			}
			sanityCheckForMethodGeneratingAnnotationsOnBuilderClass(builderType, annotationNode);
			/* generate errors for @Singular BFDs that have one already defined node. */ {
				for (BuilderFieldData bfd : builderFields) {
					SingularData sd = bfd.singularData;
					if (sd == null) continue;
					JavacSingularizer singularizer = sd.getSingularizer();
					if (singularizer == null) continue;
					if (singularizer.checkForAlreadyExistingNodesAndGenerateError(builderType, sd)) {
						bfd.singularData = null;
					}
				}
			}
		}
		
		for (BuilderFieldData bfd : builderFields) {
			if (bfd.singularData != null && bfd.singularData.getSingularizer() != null) {
				if (bfd.singularData.getSingularizer().requiresCleaning()) {
					addCleaning = true;
					break;
				}
			}
			if (bfd.obtainVia != null) {
				if (bfd.obtainVia.field().isEmpty() == bfd.obtainVia.method().isEmpty()) {
					bfd.obtainViaNode.addError("The syntax is either @ObtainVia(field = \"fieldName\") or @ObtainVia(method = \"methodName\").");
					return;
				}
				if (bfd.obtainVia.method().isEmpty() && bfd.obtainVia.isStatic()) {
					bfd.obtainViaNode.addError("@ObtainVia(isStatic = true) is not valid unless 'method' has been set.");
					return;
				}
			}
		}
		
		generateBuilderFields(builderType, builderFields, ast);
		if (addCleaning) {
			JavacTreeMaker maker = builderType.getTreeMaker();
			JCVariableDecl uncleanField = maker.VarDef(maker.Modifiers(Flags.PRIVATE), builderType.toName("$lombokUnclean"), maker.TypeIdent(CTC_BOOLEAN), null);
			injectFieldAndMarkGenerated(builderType, uncleanField);
			recursiveSetGeneratedBy(uncleanField, ast, annotationNode.getContext());
		}
		
		if (constructorExists(builderType) == MemberExistsResult.NOT_EXISTS) {
			JCMethodDecl cd = HandleConstructor.createConstructor(AccessLevel.PACKAGE, List.<JCAnnotation>nil(), builderType, List.<JavacNode>nil(), false, annotationNode);
			if (cd != null) injectMethod(builderType, cd);
		}
		
		for (BuilderFieldData bfd : builderFields) {
			makeSetterMethodsForBuilder(builderType, bfd, annotationNode, fluent, chain, accessForInners);
		}
		
		{
			MemberExistsResult methodExists = methodExists(buildMethodName, builderType, -1);
			if (methodExists == MemberExistsResult.EXISTS_BY_LOMBOK) methodExists = methodExists(buildMethodName, builderType, 0);
			if (methodExists == MemberExistsResult.NOT_EXISTS) {
				JCMethodDecl md = generateBuildMethod(tdParent, isStatic, buildMethodName, nameOfBuilderMethod, returnType, builderFields, builderType, thrownExceptions, ast, addCleaning, accessForInners);
				if (md != null) {
					injectMethod(builderType, md);
					recursiveSetGeneratedBy(md, ast, annotationNode.getContext());
				}
			}
		}
		
		if (methodExists("toString", builderType, 0) == MemberExistsResult.NOT_EXISTS) {
			java.util.List<Included<JavacNode, ToString.Include>> fieldNodes = new ArrayList<Included<JavacNode, ToString.Include>>();
			for (BuilderFieldData bfd : builderFields) {
				for (JavacNode f : bfd.createdFields) {
					fieldNodes.add(new Included<JavacNode, ToString.Include>(f, null, true));
				}
			}
			
			JCMethodDecl md = HandleToString.createToString(builderType, fieldNodes, true, false, FieldAccess.ALWAYS_FIELD, ast);
			if (md != null) injectMethod(builderType, md);
		}
		
		if (addCleaning) injectMethod(builderType, generateCleanMethod(builderFields, builderType, ast));
		
		if (generateBuilderMethod && methodExists(builderMethodName, tdParent, -1) != MemberExistsResult.NOT_EXISTS) generateBuilderMethod = false;
		if (generateBuilderMethod) {
			JCMethodDecl md = generateBuilderMethod(isStatic, builderMethodName, builderClassName, annotationNode, tdParent, typeParams, accessForOuters);
			recursiveSetGeneratedBy(md, ast, annotationNode.getContext());
			if (md != null) injectMethod(tdParent, md);
		}
		
		if (toBuilder) {
			switch (methodExists(toBuilderMethodName, tdParent, 0)) {
			case EXISTS_BY_USER:
				annotationNode.addWarning("Not generating toBuilder() as it already exists.");
				return;
			case NOT_EXISTS:
				List<JCTypeParameter> tps = typeParams;
				if (typeArgsForToBuilder != null) {
					ListBuffer<JCTypeParameter> lb = new ListBuffer<JCTypeParameter>();
					JavacTreeMaker maker = tdParent.getTreeMaker();
					for (Name n : typeArgsForToBuilder) {
						lb.append(maker.TypeParameter(n, List.<JCExpression>nil()));
					}
					tps = lb.toList();
				}
				JCMethodDecl md = generateToBuilderMethod(toBuilderMethodName, builderClassName, tdParent, tps, builderFields, fluent, ast, accessForOuters);
				if (md != null) {
					recursiveSetGeneratedBy(md, ast, annotationNode.getContext());
					injectMethod(tdParent, md);
				}
			}
		}
		
		if (nonFinalNonDefaultedFields != null && generateBuilderMethod) {
			for (JavacNode fieldNode : nonFinalNonDefaultedFields) {
				fieldNode.addWarning("@Builder will ignore the initializing expression entirely. If you want the initializing expression to serve as default, add @Builder.Default. If it is not supposed to be settable during building, make the field final.");
			}
		}
	}
	
	private static String unpack(JCExpression expr) {
		StringBuilder sb = new StringBuilder();
		unpack(sb, expr);
		return sb.toString();
	}
	
	private static void unpack(StringBuilder sb, JCExpression expr) {
		if (expr instanceof JCIdent) {
			sb.append(((JCIdent) expr).name.toString());
			return;
		}
		
		if (expr instanceof JCFieldAccess) {
			JCFieldAccess jcfa = (JCFieldAccess) expr;
			unpack(sb, jcfa.selected);
			sb.append(".").append(jcfa.name.toString());
			return;
		}
		
		if (expr instanceof JCTypeApply) {
			sb.setLength(0);
			sb.append("ERR:");
			sb.append("@Builder(toBuilder=true) is not supported if returning a type with generics applied to an intermediate.");
			sb.append("__ERR__");
			return;
		}
		
		sb.setLength(0);
		sb.append("ERR:");
		sb.append("Expected a type of some sort, not a " + expr.getClass().getName());
		sb.append("__ERR__");
	}
	
	private static final String BUILDER_TEMP_VAR = "builder";
	private JCMethodDecl generateToBuilderMethod(String toBuilderMethodName, String builderClassName, JavacNode type, List<JCTypeParameter> typeParams, java.util.List<BuilderFieldData> builderFields, boolean fluent, JCAnnotation ast, AccessLevel access) {
		// return new ThingieBuilder<A, B>().setA(this.a).setB(this.b);
		JavacTreeMaker maker = type.getTreeMaker();
		
		ListBuffer<JCExpression> typeArgs = new ListBuffer<JCExpression>();
		for (JCTypeParameter typeParam : typeParams) {
			typeArgs.append(maker.Ident(typeParam.name));
		}
		
		JCExpression call = maker.NewClass(null, List.<JCExpression>nil(), namePlusTypeParamsToTypeReference(maker, type.toName(builderClassName), typeParams), List.<JCExpression>nil(), null);
		JCExpression invoke = call;
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		for (BuilderFieldData bfd : builderFields) {
			Name setterName = fluent ? bfd.name : type.toName(HandlerUtil.buildAccessorName("set", bfd.name.toString()));
			JCExpression[] tgt = new JCExpression[bfd.singularData == null ? 1 : 2];
			if (bfd.obtainVia == null || !bfd.obtainVia.field().isEmpty()) {
				for (int i = 0; i < tgt.length; i++) {
					tgt[i] = maker.Select(maker.Ident(type.toName("this")), bfd.obtainVia == null ? bfd.rawName : type.toName(bfd.obtainVia.field()));
				}
			} else {
				if (bfd.obtainVia.isStatic()) {
					for (int i = 0; i < tgt.length; i++) {
						JCExpression c = maker.Select(maker.Ident(type.toName(type.getName())), type.toName(bfd.obtainVia.method()));
						tgt[i] = maker.Apply(typeParameterNames(maker, typeParams), c, List.<JCExpression>of(maker.Ident(type.toName("this"))));
					}
				} else {
					for (int i = 0; i < tgt.length; i++) {
						JCExpression c = maker.Select(maker.Ident(type.toName("this")), type.toName(bfd.obtainVia.method()));
						tgt[i] = maker.Apply(List.<JCExpression>nil(), c, List.<JCExpression>nil());
					}
				}
			}
			
			JCExpression arg;
			if (bfd.singularData == null) {
				arg = tgt[0];
				invoke = maker.Apply(List.<JCExpression>nil(), maker.Select(invoke, setterName), List.of(arg));
			} else {
				JCExpression isNotNull = maker.Binary(CTC_NOT_EQUAL, tgt[0], maker.Literal(CTC_BOT, null));
				JCExpression invokeBuilder = maker.Apply(List.<JCExpression>nil(), maker.Select(maker.Ident(type.toName(BUILDER_TEMP_VAR)), setterName), List.<JCExpression>of(tgt[1]));
				statements.append(maker.If(isNotNull, maker.Exec(invokeBuilder), null));
			}
		}
		if (!statements.isEmpty()) {
			JCExpression tempVarType = namePlusTypeParamsToTypeReference(maker, type.toName(builderClassName), typeParams);
			statements.prepend(maker.VarDef(maker.Modifiers(Flags.FINAL), type.toName(BUILDER_TEMP_VAR), tempVarType, invoke));
			statements.append(maker.Return(maker.Ident(type.toName(BUILDER_TEMP_VAR))));
		} else {
			statements.append(maker.Return(invoke));
		}
		JCBlock body = maker.Block(0, statements.toList());
		return maker.MethodDef(maker.Modifiers(toJavacModifier(access)), type.toName(toBuilderMethodName), namePlusTypeParamsToTypeReference(maker, type.toName(builderClassName), typeParams), List.<JCTypeParameter>nil(), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), body, null);
	}
	
	private JCMethodDecl generateCleanMethod(java.util.List<BuilderFieldData> builderFields, JavacNode type, JCTree source) {
		JavacTreeMaker maker = type.getTreeMaker();
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		
		for (BuilderFieldData bfd : builderFields) {
			if (bfd.singularData != null && bfd.singularData.getSingularizer() != null) {
				bfd.singularData.getSingularizer().appendCleaningCode(bfd.singularData, type, source, statements);
			}
		}
		
		statements.append(maker.Exec(maker.Assign(maker.Select(maker.Ident(type.toName("this")), type.toName("$lombokUnclean")), maker.Literal(CTC_BOOLEAN, 0))));
		JCBlock body = maker.Block(0, statements.toList());
		JCMethodDecl method = maker.MethodDef(maker.Modifiers(toJavacModifier(AccessLevel.PRIVATE)), type.toName("$lombokClean"), maker.Type(Javac.createVoidType(type.getSymbolTable(), CTC_VOID)), List.<JCTypeParameter>nil(), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), body, null);
		recursiveSetGeneratedBy(method, source, type.getContext());
		return method;
		/*
		 * 		if (shouldReturnThis) {
			methodType = cloneSelfType(field);
		}
		
		if (methodType == null) {
			//WARNING: Do not use field.getSymbolTable().voidType - that field has gone through non-backwards compatible API changes within javac1.6.
			methodType = treeMaker.Type(Javac.createVoidType(treeMaker, CTC_VOID));
			shouldReturnThis = false;
		}

		 */
	}
	
	private JCMethodDecl generateBuildMethod(JavacNode tdParent, boolean isStatic, String buildName, Name builderName, JCExpression returnType, java.util.List<BuilderFieldData> builderFields, JavacNode type, List<JCExpression> thrownExceptions, JCTree source, boolean addCleaning, AccessLevel access) {
		JavacTreeMaker maker = type.getTreeMaker();
		
		JCExpression call;
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		
		if (addCleaning) {
			JCExpression notClean = maker.Unary(CTC_NOT, maker.Select(maker.Ident(type.toName("this")), type.toName("$lombokUnclean")));
			JCStatement invokeClean = maker.Exec(maker.Apply(List.<JCExpression>nil(), maker.Ident(type.toName("$lombokClean")), List.<JCExpression>nil()));
			JCIf ifUnclean = maker.If(notClean, invokeClean, null);
			statements.append(ifUnclean);
		}
		
		for (BuilderFieldData bfd : builderFields) {
			if (bfd.singularData != null && bfd.singularData.getSingularizer() != null) {
				bfd.singularData.getSingularizer().appendBuildCode(bfd.singularData, type, source, statements, bfd.builderFieldName, "this");
			}
		}
		
		ListBuffer<JCExpression> args = new ListBuffer<JCExpression>();
		for (BuilderFieldData bfd : builderFields) {
			if (bfd.nameOfSetFlag != null) {
				statements.append(maker.VarDef(maker.Modifiers(0L), bfd.builderFieldName, cloneType(maker, bfd.type, source, tdParent.getContext()), maker.Select(maker.Ident(type.toName("this")), bfd.builderFieldName)));
				statements.append(maker.If(maker.Unary(CTC_NOT, maker.Ident(bfd.nameOfSetFlag)), maker.Exec(maker.Assign(maker.Ident(bfd.builderFieldName), maker.Apply(typeParameterNames(maker, ((JCClassDecl) tdParent.get()).typarams), maker.Select(maker.Ident(((JCClassDecl) tdParent.get()).name), bfd.nameOfDefaultProvider), List.<JCExpression>nil()))), null));
			}
			args.append(maker.Ident(bfd.builderFieldName));
		}
		
		if (addCleaning) {
			statements.append(maker.Exec(maker.Assign(maker.Select(maker.Ident(type.toName("this")), type.toName("$lombokUnclean")), maker.Literal(CTC_BOOLEAN, 1))));
		}
		
		if (builderName == null) {
			call = maker.NewClass(null, List.<JCExpression>nil(), returnType, args.toList(), null);
			statements.append(maker.Return(call));
		} else {
			ListBuffer<JCExpression> typeParams = new ListBuffer<JCExpression>();
			for (JCTypeParameter tp : ((JCClassDecl) type.get()).typarams) {
				typeParams.append(maker.Ident(tp.name));
			}
			JCExpression callee = maker.Ident(((JCClassDecl) type.up().get()).name);
			if (!isStatic) callee = maker.Select(callee, type.up().toName("this"));
			JCExpression fn = maker.Select(callee, builderName);
			call = maker.Apply(typeParams.toList(), fn, args.toList());
			if (returnType instanceof JCPrimitiveTypeTree && CTC_VOID.equals(typeTag(returnType))) {
				statements.append(maker.Exec(call));
			} else {
				statements.append(maker.Return(call));
			}
		}
		
		JCBlock body = maker.Block(0, statements.toList());
		
		return maker.MethodDef(maker.Modifiers(toJavacModifier(access)), type.toName(buildName), returnType, List.<JCTypeParameter>nil(), List.<JCVariableDecl>nil(), thrownExceptions, body, null);
	}
	
	public static JCMethodDecl generateDefaultProvider(Name methodName, JavacNode fieldNode, List<JCTypeParameter> params) {
		JavacTreeMaker maker = fieldNode.getTreeMaker();
		JCVariableDecl field = (JCVariableDecl) fieldNode.get();
		
		JCStatement statement = maker.Return(field.init);
		field.init = null;
		
		JCBlock body = maker.Block(0, List.<JCStatement>of(statement));
		int modifiers = Flags.PRIVATE | Flags.STATIC;
		return maker.MethodDef(maker.Modifiers(modifiers), methodName, cloneType(maker, field.vartype, field, fieldNode.getContext()), copyTypeParams(fieldNode, params), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), body, null);
	}
	
	public JCMethodDecl generateBuilderMethod(boolean isStatic, String builderMethodName, String builderClassName, JavacNode source, JavacNode type, List<JCTypeParameter> typeParams, AccessLevel access) {
		JavacTreeMaker maker = type.getTreeMaker();
		
		ListBuffer<JCExpression> typeArgs = new ListBuffer<JCExpression>();
		for (JCTypeParameter typeParam : typeParams) {
			typeArgs.append(maker.Ident(typeParam.name));
		}
		
		JCExpression call = maker.NewClass(null, List.<JCExpression>nil(), namePlusTypeParamsToTypeReference(maker, type.toName(builderClassName), typeParams), List.<JCExpression>nil(), null);
		JCStatement statement = maker.Return(call);
		
		JCBlock body = maker.Block(0, List.<JCStatement>of(statement));
		int modifiers = toJavacModifier(access);
		if (isStatic) modifiers |= Flags.STATIC;
		return maker.MethodDef(maker.Modifiers(modifiers), type.toName(builderMethodName), namePlusTypeParamsToTypeReference(maker, type.toName(builderClassName), typeParams), copyTypeParams(source, typeParams), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), body, null);
	}
	
	public void generateBuilderFields(JavacNode builderType, java.util.List<BuilderFieldData> builderFields, JCTree source) {
		int len = builderFields.size();
		java.util.List<JavacNode> existing = new ArrayList<JavacNode>();
		for (JavacNode child : builderType.down()) {
			if (child.getKind() == Kind.FIELD) existing.add(child);
		}
		
		java.util.List<JCVariableDecl> generated = new ArrayList<JCVariableDecl>();
		
		for (int i = len - 1; i >= 0; i--) {
			BuilderFieldData bfd = builderFields.get(i);
			if (bfd.singularData != null && bfd.singularData.getSingularizer() != null) {
				bfd.createdFields.addAll(bfd.singularData.getSingularizer().generateFields(bfd.singularData, builderType, source));
			} else {
				JavacNode field = null, setFlag = null;
				for (JavacNode exists : existing) {
					Name n = ((JCVariableDecl) exists.get()).name;
					if (n.equals(bfd.builderFieldName)) field = exists;
					if (n.equals(bfd.nameOfSetFlag)) setFlag = exists;
				}
				JavacTreeMaker maker = builderType.getTreeMaker();
				if (field == null) {
					JCModifiers mods = maker.Modifiers(Flags.PRIVATE);
					JCVariableDecl newField = maker.VarDef(mods, bfd.builderFieldName, cloneType(maker, bfd.type, source, builderType.getContext()), null);
					field = injectFieldAndMarkGenerated(builderType, newField);
					generated.add(newField);
				}
				if (setFlag == null && bfd.nameOfSetFlag != null) {
					JCModifiers mods = maker.Modifiers(Flags.PRIVATE);
					JCVariableDecl newField = maker.VarDef(mods, bfd.nameOfSetFlag, maker.TypeIdent(CTC_BOOLEAN), null);
					injectFieldAndMarkGenerated(builderType, newField);
					generated.add(newField);
				}
				bfd.createdFields.add(field);
			}
		}
		for (JCVariableDecl gen : generated)  recursiveSetGeneratedBy(gen, source, builderType.getContext());
	}
	
	public void makeSetterMethodsForBuilder(JavacNode builderType, BuilderFieldData fieldNode, JavacNode source, boolean fluent, boolean chain, AccessLevel access) {
		boolean deprecate = isFieldDeprecated(fieldNode.originalFieldNode);
		if (fieldNode.singularData == null || fieldNode.singularData.getSingularizer() == null) {
			makeSimpleSetterMethodForBuilder(builderType, deprecate, fieldNode.createdFields.get(0), fieldNode.name, fieldNode.nameOfSetFlag, source, fluent, chain, fieldNode.annotations, fieldNode.originalFieldNode, access);
		} else {
			fieldNode.singularData.getSingularizer().generateMethods(fieldNode.singularData, deprecate, builderType, source.get(), fluent, chain, access);
		}
	}
	
	private void makeSimpleSetterMethodForBuilder(JavacNode builderType, boolean deprecate, JavacNode fieldNode, Name paramName, Name nameOfSetFlag, JavacNode source, boolean fluent, boolean chain, List<JCAnnotation> annosOnParam, JavacNode originalFieldNode, AccessLevel access) {
		Name fieldName = ((JCVariableDecl) fieldNode.get()).name;
		
		for (JavacNode child : builderType.down()) {
			if (child.getKind() != Kind.METHOD) continue;
			JCMethodDecl methodDecl = (JCMethodDecl) child.get();
			Name existingName = methodDecl.name;
			if (existingName.equals(fieldName) && !isTolerate(fieldNode, methodDecl)) return;
		}
		
		String setterName = fluent ? paramName.toString() : HandlerUtil.buildAccessorName("set", paramName.toString());
		
		JavacTreeMaker maker = fieldNode.getTreeMaker();
		
		List<JCAnnotation> methodAnns = JavacHandlerUtil.findCopyableToSetterAnnotations(originalFieldNode);
		JCMethodDecl newMethod = HandleSetter.createSetter(toJavacModifier(access), deprecate, fieldNode, maker, setterName, paramName, nameOfSetFlag, chain, source, methodAnns, annosOnParam);
		recursiveSetGeneratedBy(newMethod, source.get(), builderType.getContext());
		copyJavadoc(originalFieldNode, newMethod, CopyJavadoc.SETTER);
		
		injectMethod(builderType, newMethod);
	}
	
	public JavacNode makeBuilderClass(boolean isStatic, JavacNode source, JavacNode tdParent, String builderClassName, List<JCTypeParameter> typeParams, JCAnnotation ast, AccessLevel access) {
		JavacTreeMaker maker = tdParent.getTreeMaker();
		int modifiers = toJavacModifier(access);
		if (isStatic) modifiers |= Flags.STATIC;
		JCModifiers mods = maker.Modifiers(modifiers);
		JCClassDecl builder = maker.ClassDef(mods, tdParent.toName(builderClassName), copyTypeParams(source, typeParams), null, List.<JCExpression>nil(), List.<JCTree>nil());
		return injectType(tdParent, builder);
	}
	
	private void addObtainVia(BuilderFieldData bfd, JavacNode node) {
		for (JavacNode child : node.down()) {
			if (!annotationTypeMatches(ObtainVia.class, child)) continue;
			AnnotationValues<ObtainVia> ann = createAnnotation(ObtainVia.class, child);
			bfd.obtainVia = ann.getInstance();
			bfd.obtainViaNode = child;
			deleteAnnotationIfNeccessary(child, ObtainVia.class);
			return;
		}
	}
	
	/**
	 * Returns the explicitly requested singular annotation on this node (field
	 * or parameter), or null if there's no {@code @Singular} annotation on it.
	 * 
	 * @param node The node (field or method param) to inspect for its name and potential {@code @Singular} annotation.
	 */
	private SingularData getSingularData(JavacNode node) {
		for (JavacNode child : node.down()) {
			if (!annotationTypeMatches(Singular.class, child)) continue;
			Name pluralName = node.getKind() == Kind.FIELD ? removePrefixFromField(node) : ((JCVariableDecl) node.get()).name;
			AnnotationValues<Singular> ann = createAnnotation(Singular.class, child);
			deleteAnnotationIfNeccessary(child, Singular.class);
			String explicitSingular = ann.getInstance().value();
			if (explicitSingular.isEmpty()) {
				if (Boolean.FALSE.equals(node.getAst().readConfiguration(ConfigurationKeys.SINGULAR_AUTO))) {
					node.addError("The singular must be specified explicitly (e.g. @Singular(\"task\")) because auto singularization is disabled.");
					explicitSingular = pluralName.toString();
				} else {
					explicitSingular = autoSingularize(pluralName.toString());
					if (explicitSingular == null) {
						node.addError("Can't singularize this name; please specify the singular explicitly (i.e. @Singular(\"sheep\"))");
						explicitSingular = pluralName.toString();
					}
				}
			}
			Name singularName = node.toName(explicitSingular);
			
			JCExpression type = null;
			if (node.get() instanceof JCVariableDecl) {
				type = ((JCVariableDecl) node.get()).vartype;
			}
			
			String name = null;
			List<JCExpression> typeArgs = List.nil();
			if (type instanceof JCTypeApply) {
				typeArgs = ((JCTypeApply) type).arguments;
				type = ((JCTypeApply) type).clazz;
			}
			
			name = type.toString();
			
			String targetFqn = JavacSingularsRecipes.get().toQualified(name);
			JavacSingularizer singularizer = JavacSingularsRecipes.get().getSingularizer(targetFqn, node);
			if (singularizer == null) {
				node.addError("Lombok does not know how to create the singular-form builder methods for type '" + name + "'; they won't be generated.");
				return null;
			}
			
			return new SingularData(child, singularName, pluralName, typeArgs, targetFqn, singularizer);
		}
		
		return null;
	}
}
