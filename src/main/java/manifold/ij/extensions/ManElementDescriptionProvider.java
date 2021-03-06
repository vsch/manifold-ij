package manifold.ij.extensions;

import com.intellij.psi.ElementDescriptionLocation;
import com.intellij.psi.ElementDescriptionProvider;
import com.intellij.psi.PsiElement;
import com.intellij.usageView.UsageViewTypeLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ManElementDescriptionProvider implements ElementDescriptionProvider
{
  @Nullable
  @Override
  public String getElementDescription( @NotNull PsiElement element, @NotNull ElementDescriptionLocation location )
  {
    if( element instanceof FakeTargetElement && location instanceof UsageViewTypeLocation )
    {
      // Used in the refactor/usage dialogs
      return ((FakeTargetElement)element).getKind();
    }

    return null;
  }
}
