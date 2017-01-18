/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool.expr;

import android.databinding.tool.processing.Scope;
import android.databinding.tool.reflection.Callable;
import android.databinding.tool.reflection.Callable.Type;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.solver.ExecutionPath;
import android.databinding.tool.store.SetterStore;
import android.databinding.tool.util.L;
import android.databinding.tool.writer.KCode;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static android.databinding.tool.reflection.Callable.DYNAMIC;
import static android.databinding.tool.reflection.Callable.STATIC;


public class MethodCallExpr extends Expr {
    final String mName;
    Callable mGetter;
    ModelMethod mMethod;
    // Allow protected calls -- only used for ViewDataBinding methods.
    private boolean mAllowProtected;

    static List<Expr> concat(Expr e, List<Expr> list) {
        List<Expr> merged = new ArrayList<Expr>();
        merged.add(e);
        merged.addAll(list);
        return merged;
    }

    MethodCallExpr(Expr target, String name, List<Expr> args) {
        super(concat(target, args));
        mName = name;
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void updateExpr(ModelAnalyzer modelAnalyzer) {
        try {
            Scope.enter(this);
            getResolvedType();
            super.updateExpr(modelAnalyzer);
        } finally {
            Scope.exit();
        }
    }

    @Override
    protected KCode generateCode() {
        KCode code = new KCode()
                .app("", getTarget().toCode())
                .app(".")
                .app(getGetter().name)
                .app("(");
        appendArgs(code);
        code.app(")");
        return code;
    }

    @Override
    public Expr cloneToModel(ExprModel model) {
        return model.methodCall(getTarget().cloneToModel(model), mName,
                cloneToModel(model, getArgs()));
    }

    private void appendArgs(KCode code) {
        boolean first = true;
        for (Expr arg : getArgs()) {
            if (first) {
                first = false;
            } else {
                code.app(", ");
            }
            code.app("", arg.toCode());
        }
    }

    @Override
    public List<ExecutionPath> toExecutionPath(List<ExecutionPath> paths) {
        final List<ExecutionPath> targetPaths = getTarget().toExecutionPath(paths);
        // after this, we need a null check.
        List<ExecutionPath> result = new ArrayList<ExecutionPath>();
        if (getTarget() instanceof StaticIdentifierExpr) {
            result.addAll(toExecutionPathInOrder(paths, getArgs()));
        } else {
            for (ExecutionPath path : targetPaths) {
                Expr cmp = getModel()
                        .comparison("!=", getTarget(), getModel().symbol("null", Object.class));
                cmp.setUnwrapObservableFields(false);
                path.addPath(cmp);
                final ExecutionPath subPath = path.addBranch(cmp, true);
                if (subPath != null) {
                    result.addAll(toExecutionPathInOrder(subPath, getArgs()));
                }
            }
        }
        return result;
    }

    private List<ExecutionPath> toExecutionPathInOrder(ExecutionPath path, List<Expr> args) {
        return toExecutionPathInOrder(Arrays.asList(path), args);
    }

    @Override
    public void injectSafeUnboxing(ModelAnalyzer modelAnalyzer, ExprModel model) {
        ModelMethod method = mMethod;
        int limit = getArgs().size();
        for (int i = 0; i < limit; i++) {
            Expr arg = getArgs().get(i);
            ModelClass expected = method.getParameterAt(i);
            if (arg.getResolvedType().isNullable() && !expected.isNullable()) {
                safeUnboxChild(model, arg);
            }
        }
    }

    @Override
    protected void resetResolvedType() {
        super.resetResolvedType();
        mGetter = null;
    }

    @Override
    protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
        if (mGetter == null) {
            List<ModelClass> args = new ArrayList<ModelClass>();
            for (Expr expr : getArgs()) {
                args.add(expr.getResolvedType());
            }

            Expr target = getTarget();
            boolean isStatic = target instanceof StaticIdentifierExpr;
            mMethod = target.getResolvedType().getMethod(mName, args, isStatic, mAllowProtected);
            if (mMethod == null) {
                StringBuilder argTypes = new StringBuilder();
                for (ModelClass arg : args) {
                    if (argTypes.length() != 0) {
                        argTypes.append(", ");
                    }
                    argTypes.append(arg.toJavaCode());
                }
                String message = "cannot find method '" + mName + "(" + argTypes + ")' in class " +
                        target.getResolvedType().toJavaCode();
                IllegalArgumentException e = new IllegalArgumentException(message);
                L.e(e, "cannot find method %s(%s) in class %s", mName, argTypes,
                        target.getResolvedType().toJavaCode());
                throw e;
            }
            if (!isStatic && mMethod.isStatic()) {
                // found a static method on an instance. Use class instead
                target.getParents().remove(this);
                getChildren().remove(target);
                StaticIdentifierExpr staticId = getModel()
                        .staticIdentifierFor(target.getResolvedType());
                getChildren().add(staticId);
                staticId.getParents().add(this);
                // make sure we update this in case we access it below
                target = getTarget();
            }
            int flags = DYNAMIC;
            if (mMethod.isStatic()) {
                flags |= STATIC;
            }
            mGetter = new Callable(Type.METHOD, mMethod.getName(), null, mMethod.getReturnType(args),
                    mMethod.getParameterTypes().length, flags, mMethod, null);
        }
        return mGetter.resolvedType;
    }

    @Override
    protected List<Dependency> constructDependencies() {
        final List<Dependency> dependencies = constructDynamicChildrenDependencies();
        for (Dependency dependency : dependencies) {
            if (dependency.getOther() == getTarget()) {
                dependency.setMandatory(true);
            }
        }
        return dependencies;
    }

    @Override
    protected String computeUniqueKey() {
        return join(getTarget(), ".", mName, join(getArgs()));
    }

    public Expr getTarget() {
        return getChildren().get(0);
    }

    public String getName() {
        return mName;
    }

    public List<Expr> getArgs() {
        return getChildren().subList(1, getChildren().size());
    }

    public Callable getGetter() {
        return mGetter;
    }

    public void setAllowProtected() {
        mAllowProtected = true;
    }

    @Override
    public String getInvertibleError() {
        final SetterStore setterStore = SetterStore.get(ModelAnalyzer.getInstance());
        getResolvedType(); // ensure mMethod has been set
        if (mMethod == null) {
            return "Could not find the method " + mName + " to inverse for two-way binding";
        }
        if (mName.equals("get") && getTarget().getResolvedType().isObservableField() &&
                getArgs().isEmpty()) {
            return null;
        }
        String inverse = setterStore.getInverseMethod(mMethod);
        if (inverse == null) {
            return "There is no inverse for method " + mName + ", you must add an " +
                    "@InverseMethod annotation to the method to indicate which method " +
                    "should be used when using it in two-way binding expressions";
        }
        return null;
    }

    @Override
    public Expr generateInverse(ExprModel model, Expr value, String bindingClassName) {
        getResolvedType(); // ensure mMethod has been resolved.
        if (mName.equals("get") && getTarget().getResolvedType().isObservableField() &&
                getArgs().isEmpty()) {
            Expr castExpr = model.castExpr(getResolvedType().toJavaCode(), value);
            Expr target = getTarget().cloneToModel(model);
            Expr inverse = model.methodCall(target, "set", Lists.newArrayList(castExpr));
            inverse.setUnwrapObservableFields(false);
            return inverse;
        }
        SetterStore setterStore = SetterStore.get(ModelAnalyzer.getInstance());
        String methodName = setterStore.getInverseMethod(mMethod);
        List<Expr> theseArgs = getArgs();
        List<Expr> args = new ArrayList<>();
        for (int i = 0; i < theseArgs.size() - 1; i++) {
            args.add(theseArgs.get(i).cloneToModel(model));
        }
        args.add(value);
        Expr varExpr = theseArgs.get(theseArgs.size() - 1).cloneToModel(model);
        Expr methodCall = model.methodCall(getTarget().cloneToModel(model), methodName, args);
        return varExpr.generateInverse(model, methodCall, bindingClassName);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(getTarget())
                .append('.')
                .append(mName)
                .append('(');
        final List<Expr> args = getArgs();
        for (int i = 0; i < args.size(); i++) {
            Expr arg = args.get(i);
            if (i != 0) {
                buf.append(", ");
            }
            buf.append(arg);
        }
        buf.append(')');
        return buf.toString();
    }
}
