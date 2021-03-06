// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.syntax;

import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An Environment for the semantic checking of Skylark files.
 *
 * @see Statement#validate
 * @see Expression#validate
 */
public final class ValidationEnvironment {

  private class Scope {
    private final Set<String> variables = new HashSet<>();
    private final Set<String> readOnlyVariables = new HashSet<>();
    @Nullable private final Scope parent;

    Scope(@Nullable Scope parent) {
      this.parent = parent;
    }
  }

  private final SkylarkSemanticsOptions semantics;
  private Scope scope;

  /** Create a ValidationEnvironment for a given global Environment. */
  ValidationEnvironment(Environment env) {
    Preconditions.checkArgument(env.isGlobal());
    scope = new Scope(null);
    Set<String> builtinVariables = env.getVariableNames();
    scope.variables.addAll(builtinVariables);
    scope.readOnlyVariables.addAll(builtinVariables);
    semantics = env.getSemantics();
  }

  /** Returns true if this ValidationEnvironment is top level i.e. has no parent. */
  boolean isTopLevel() {
    return scope.parent == null;
  }

  SkylarkSemanticsOptions getSemantics() {
    return semantics;
  }

  /** Declare a variable and add it to the environment. */
  void declare(String varname, Location location) throws EvalException {
    checkReadonly(varname, location);
    if (scope.parent == null) {  // top-level values are immutable
      scope.readOnlyVariables.add(varname);
    }
    scope.variables.add(varname);
  }

  private void checkReadonly(String varname, Location location) throws EvalException {
    if (scope.readOnlyVariables.contains(varname)) {
      throw new EvalException(
          location,
          String.format("Variable %s is read only", varname),
          "https://bazel.build/versions/master/docs/skylark/errors/read-only-variable.html");
    }
  }

  /** Returns true if the symbol exists in the validation environment (or a parent). */
  boolean hasSymbolInEnvironment(String varname) {
    for (Scope s = scope; s != null; s = s.parent) {
      if (s.variables.contains(varname)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the set of all accessible symbols (both local and global) */
  Set<String> getAllSymbols() {
    Set<String> all = new HashSet<>();
    for (Scope s = scope; s != null; s = s.parent) {
      all.addAll(s.variables);
    }
    return all;
  }

 /** Throws EvalException if a load() appears after another kind of statement. */
  private static void checkLoadAfterStatement(List<Statement> statements) throws EvalException {
    Location firstStatement = null;

    for (Statement statement : statements) {
      // Ignore string literals (e.g. docstrings).
      if (statement instanceof ExpressionStatement
          && ((ExpressionStatement) statement).getExpression() instanceof StringLiteral) {
        continue;
      }

      if (statement instanceof LoadStatement) {
        if (firstStatement == null) {
          continue;
        }
        throw new EvalException(
            statement.getLocation(),
            "load() statements must be called before any other statement. "
                + "First non-load() statement appears at "
                + firstStatement
                + ". Use --incompatible_bzl_disallow_load_after_statement=false to temporarily "
                + "disable this check.");
      }

      if (firstStatement == null) {
        firstStatement = statement.getLocation();
      }
    }
  }

  /** Throws EvalException if a `if` statement appears at the top level. */
  private static void checkToplevelIfStatement(List<Statement> statements) throws EvalException {
    for (Statement statement : statements) {
      if (statement instanceof IfStatement) {
        throw new EvalException(
            statement.getLocation(),
            "if statements are not allowed at the top level. You may move it inside a function "
                + "or use an if expression (x if condition else y). "
                + "Use --incompatible_disallow_toplevel_if_statement=false to temporarily disable "
                + "this check.");
      }
    }
  }

  /** Validates the AST and runs static checks. */
  void validateAst(List<Statement> statements) throws EvalException {
    // Check that load() statements are on top.
    if (semantics.incompatibleBzlDisallowLoadAfterStatement) {
      checkLoadAfterStatement(statements);
    }

    // Check that load() statements are on top.
    if (semantics.incompatibleDisallowToplevelIfStatement) {
      checkToplevelIfStatement(statements);
    }

    // Add every function in the environment before validating. This is
    // necessary because functions may call other functions defined
    // later in the file.
    for (Statement statement : statements) {
      if (statement instanceof FunctionDefStatement) {
        FunctionDefStatement fct = (FunctionDefStatement) statement;
        declare(fct.getIdentifier().getName(), fct.getLocation());
      }
    }

    for (Statement statement : statements) {
      statement.validate(this);
    }
  }

  public static void validateAst(Environment env, List<Statement> statements) throws EvalException {
    ValidationEnvironment venv = new ValidationEnvironment(env);
    venv.validateAst(statements);
    // Check that no closeScope was forgotten.
    Preconditions.checkState(venv.scope.parent == null);
  }

  public static boolean validateAst(
      Environment env, List<Statement> statements, EventHandler eventHandler) {
    try {
      validateAst(env, statements);
      return true;
    } catch (EvalException e) {
      if (!e.isDueToIncompleteAST()) {
        eventHandler.handle(Event.error(e.getLocation(), e.getMessage()));
      }
      return false;
    }
  }

  /** Open a new scope that will contain the future declarations. */
  public void openScope() {
    this.scope = new Scope(this.scope);
  }

  /** Close a scope (and lose all declarations it contained). */
  public void closeScope() {
    this.scope = Preconditions.checkNotNull(this.scope.parent);
  }
}
