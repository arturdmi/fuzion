/*

This file is part of the Fuzion language implementation.

The Fuzion language implementation is free software: you can redistribute it
and/or modify it under the terms of the GNU General Public License as published
by the Free Software Foundation, version 3 of the License.

The Fuzion language implementation is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
License for more details.

You should have received a copy of the GNU General Public License along with The
Fuzion language implementation.  If not, see <https://www.gnu.org/licenses/>.

*/

/*-----------------------------------------------------------------------
 *
 * Tokiwa Software GmbH, Germany
 *
 * Source of class IdentifierClassifier
 *
 *---------------------------------------------------------------------*/

package dev.flang.lsp.shared;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Optional;

import dev.flang.ast.Feature;
import dev.flang.ast.State;
import dev.flang.ast.AbstractCall;
import dev.flang.ast.AbstractFeature;
import dev.flang.ast.AbstractType;
import dev.flang.lsp.enums.TokenType;
import dev.flang.lsp.shared.records.TokenInfo;
import dev.flang.util.ANY;
import dev.flang.util.HasSourcePosition;
import dev.flang.util.SourcePosition;


/**
 * Classifies identifier tokens of one source file for semantic highlighting.
 *
 * All AST lookups are done ONCE in the constructor, so classifying a token is
 * a hash map lookup. Create one instance per semanticTokens request.
 */
public class IdentifierClassifier extends ANY
{

  /**
   * byte position of a name in the source file -> features/calls whose name
   * starts there. A list since several AST items may share one position.
   */
  private final Map<Integer, List<HasSourcePosition>> _itemsAt;


  /**
   * features of this file that are used as a choice element, e.g. `nil` in
   * `option T : choice T nil`. Computed once instead of per token.
   */
  private final Set<AbstractFeature> _choiceMembers;


  public IdentifierClassifier(URI uri)
  {
    var items = ASTWalker
      .traverse(uri)
      .map(Entry::getKey)
      .filter(x -> x instanceof AbstractFeature || x instanceof AbstractCall)
      .filter(HasSourcePositionTool.isItemInFile(uri))
      .toList();

    _itemsAt = items
      .stream()
      .collect(Collectors.groupingBy(x -> namePos(x).bytePos()));

    _choiceMembers = items
      .stream()
      .filter(x -> x instanceof AbstractFeature)
      .map(x -> (AbstractFeature) x)
      .flatMap(f -> f.hasResultField()
               ? Stream.of(f.selfType(), f.resultType())
               : Stream.of(f.selfType()))
      .filter(AbstractType::isChoice)
      .flatMap(t -> t.choiceGenerics().stream())
      .map(AbstractType::feature)
      .collect(Collectors.toSet());
  }


  /**
   * The semantic token type of identifier token t, empty if unknown.
   */
  public Optional<TokenType> tokenType(TokenInfo t)
  {
    return _itemsAt
      .getOrDefault(t.start().bytePos(), List.of())
      .stream()
      // skip generated items that happen to share the position
      .filter(x -> t.text().equals(name(x)))
      .findFirst()
      .flatMap(this::tokenType);
  }


  private Optional<TokenType> tokenType(HasSourcePosition item)
  {
    return item instanceof AbstractCall c
      ? tokenTypeOfCall(c)
      : tokenTypeOfFeature((AbstractFeature) item);
  }


  private Optional<TokenType> tokenTypeOfCall(AbstractCall c)
  {
    if (c.isInheritanceCall())
      {
        return Optional.of(TokenType.Interface);
      }
    if (CallTool.isFixLikeCall(c))
      {
        return Optional.of(TokenType.Operator);
      }
    return tokenTypeOfFeature(c.calledFeature());
  }


  private Optional<TokenType> tokenTypeOfFeature(AbstractFeature af)
  {
    if (af instanceof Feature f && f.state() == State.ERROR)
      {
        return Optional.empty();
      }
    return Optional.of(switch (af.kind())
      {
      case ExplicitTypeParameter, FreeTypeParameter, OpenTypeParameter -> TokenType.TypeParameter;
      case Field     -> FeatureTool.isArgument(af)                         ? TokenType.Parameter
                      : af.outer() != null && !af.outer().isConstructor() ? TokenType.Variable   // local in a routine
                      :                                                     TokenType.Property;
      case Choice    -> TokenType.Enum;
      case Native    -> TokenType.Method;
      case Function, Intrinsic, Abstract, Constructor, RefConstructor -> routineType(af);
      });
  }


  private TokenType routineType(AbstractFeature af)
  {
    if (af.isConstructor()
        && af.valueArguments().isEmpty()
        && af.code().containsOnlyDeclarations()
        && !FeatureTool.doesInherit(af))
      {
        return ParserTool.declaredFeatures(af).findAny().isPresent() ? TokenType.Namespace
             : _choiceMembers.contains(af)                           ? TokenType.EnumMember
             :                                                         TokenType.Type;
      }
    return af.isConstructor()                                                        ? TokenType.Class
         : FeatureTool.outerFeatures(af).allMatch(o -> o.valueArguments().isEmpty()) ? TokenType.Function
         :                                                                             TokenType.Method;
  }


  /**
   * Name of the feature as written in the source.  NYI: step 3 handles
   * generated wrappers.
   */
  private static String name(HasSourcePosition x)
  {
    return FeatureTool.bareName(x instanceof AbstractCall c ? c.calledFeature() : (AbstractFeature) x);
  }


  /**
   * Position of the name of x: for a feature its bare name (pos() may point
   * to a modifier like `public` or `infix`), for a call its pos().
   */
  private static SourcePosition namePos(HasSourcePosition x)
  {
    return x instanceof AbstractFeature af ? FeatureTool.bareNamePosition(af) : x.pos();
  }

}
