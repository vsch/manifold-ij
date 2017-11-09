package manifold.ij.extensions;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import manifold.ij.core.ManModule;
import manifold.ij.core.ManProject;

/**
 */
public class ManifoldPsiClassAnnotator implements Annotator
{
  @Override
  public void annotate( PsiElement element, AnnotationHolder holder )
  {
    if( DumbService.getInstance( element.getProject() ).isDumb() )
    {
      // skip processing during index rebuild
      return;
    }

    List<PsiElement> javaElems = ResourceToManifoldUtil.findJavaElementsFor( element );
    for( PsiElement javaElem: javaElems )
    {
      PsiClass psiClass = getContainingClass( javaElem );
      if( psiClass == null )
      {
        continue;
      }

      ManModule manModule = ManProject.getModule( element );
      PsiClass manClass = ManifoldPsiClassCache.instance().getPsiClass( GlobalSearchScope.moduleScope( manModule.getIjModule() ), manModule, psiClass.getQualifiedName() );
      if( !(manClass instanceof ManifoldPsiClass) )
      {
        continue;
      }

      DiagnosticCollector issues = ((ManifoldPsiClass)manClass).getIssues();
      if( issues == null )
      {
        continue;
      }

      for( Object issue : issues.getDiagnostics() )
      {
        Diagnostic d = (Diagnostic)issue;

        if( element.getTextOffset() > d.getStartPosition() ||
            element.getTextOffset() + element.getTextLength() <= d.getStartPosition() )
        {
          continue;
        }

        TextRange range = new TextRange( element.getTextRange().getStartOffset(),
                                         element.getTextRange().getEndOffset() );
        switch( d.getKind() )
        {
          case ERROR:
            holder.createErrorAnnotation( range, d.getMessage( Locale.getDefault() ) );
            break;
          case WARNING:
          case MANDATORY_WARNING:
            holder.createWarningAnnotation( range, d.getMessage( Locale.getDefault() ) );
            break;
          case NOTE:
          case OTHER:
            holder.createInfoAnnotation( range, d.getMessage( Locale.getDefault() ) );
            break;
        }
      }
    }
  }

  protected static PsiClass getContainingClass( PsiElement element )
  {
    final PsiClass psiClass = PsiTreeUtil.getParentOfType( element, PsiClass.class, false );
    if( psiClass == null )
    {
      final PsiFile containingFile = element.getContainingFile();
      if( containingFile instanceof PsiClassOwner )
      {
        final PsiClass[] classes = ((PsiClassOwner)containingFile).getClasses();
        if( classes.length == 1 )
        {
          return classes[0];
        }
      }
    }
    return psiClass;
  }
}