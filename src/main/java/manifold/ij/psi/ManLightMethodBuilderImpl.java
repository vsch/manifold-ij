package manifold.ij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.impl.CheckUtil;
import com.intellij.psi.impl.light.LightIdentifier;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightModifierList;
import com.intellij.psi.impl.light.LightParameterListBuilder;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.StringBuilderSpinAllocator;
import org.jetbrains.annotations.NotNull;

/**
 */
public class ManLightMethodBuilderImpl extends LightMethodBuilder implements ManLightMethodBuilder
{
  private LightIdentifier _nameIdentifier;
  private ASTNode _astNode;

  public ManLightMethodBuilderImpl( PsiManager manager, String name )
  {
    super( manager, JavaLanguage.INSTANCE, name,
           new LightParameterListBuilder( manager, JavaLanguage.INSTANCE ), new ManLightModifierListImpl( manager, JavaLanguage.INSTANCE ) );
    _nameIdentifier = new LightIdentifier( manager, name );
  }

  @Override
  public ManLightMethodBuilder withNavigationElement( PsiElement navigationElement )
  {
    setNavigationElement( navigationElement );
    return this;
  }

  @Override
  public ManLightMethodBuilder withModifier( @PsiModifier.ModifierConstant String modifier )
  {
    addModifier( modifier );
    return this;
  }

  @Override
  public ManLightMethodBuilder withMethodReturnType( PsiType returnType )
  {
    setMethodReturnType( returnType );
    return this;
  }

  @Override
  public ManLightMethodBuilder withParameter( String name, PsiType type )
  {
    addParameter( new ManLightParameterImpl( name, type, this, JavaLanguage.INSTANCE ) );
    return this;
  }

  @Override
  public ManLightMethodBuilder withException( PsiClassType type )
  {
    addException( type );
    return this;
  }

  @Override
  public ManLightMethodBuilder withException( String fqName )
  {
    addException( fqName );
    return this;
  }

  @Override
  public ManLightMethodBuilder withContainingClass( PsiClass containingClass )
  {
    setContainingClass( containingClass );
    return this;
  }

  @Override
  public ManLightMethodBuilder withTypeParameter( PsiTypeParameter typeParameter )
  {
    addTypeParameter( typeParameter );
    return this;
  }

  @Override
  public PsiIdentifier getNameIdentifier()
  {
    return _nameIdentifier;
  }

  @Override
  public PsiElement getParent()
  {
    PsiElement result = super.getParent();
    result = null != result ? result : getContainingClass();
    return result;
  }

  @Override
  public PsiFile getContainingFile()
  {
    PsiClass containingClass = getContainingClass();
    return containingClass != null ? containingClass.getContainingFile() : null;
  }

  @Override
  public String getText()
  {
    ASTNode node = getNode();
    if( null != node )
    {
      return node.getText();
    }
    return "";
  }

  @Override
  public ASTNode getNode()
  {
    if( null == _astNode )
    {
      _astNode = rebuildMethodFromString().getNode();
    }
    return _astNode;
  }

  private PsiMethod rebuildMethodFromString()
  {
    final StringBuilder builder = StringBuilderSpinAllocator.alloc();
    try
    {
      builder.append( getAllModifierProperties( (LightModifierList)getModifierList() ) );
      PsiType returnType = getReturnType();
      if( null != returnType )
      {
        builder.append( returnType.getCanonicalText() ).append( ' ' );
      }
      builder.append( getName() );
      builder.append( '(' );
      if( getParameterList().getParametersCount() > 0 )
      {
        for( PsiParameter parameter : getParameterList().getParameters() )
        {
          builder.append( parameter.getType().getCanonicalText() ).append( ' ' ).append( parameter.getName() ).append( ',' );
        }
        builder.deleteCharAt( builder.length() - 1 );
      }
      builder.append( ')' );
      builder.append( '{' ).append( "  " ).append( '}' );

      PsiElementFactory elementFactory = JavaPsiFacade.getInstance( getManager().getProject() ).getElementFactory();
      return elementFactory.createMethodFromText( builder.toString(), getContainingClass() );
    }
    finally
    {
      StringBuilderSpinAllocator.dispose( builder );
    }
  }

  public String getAllModifierProperties( LightModifierList modifierList )
  {
    final StringBuilder builder = StringBuilderSpinAllocator.alloc();
    try
    {
      for( String modifier : modifierList.getModifiers() )
      {
        if( !PsiModifier.PACKAGE_LOCAL.equals( modifier ) )
        {
          builder.append( modifier ).append( ' ' );
        }
      }
      return builder.toString();
    }
    finally
    {
      StringBuilderSpinAllocator.dispose( builder );
    }
  }

  public PsiElement copy()
  {
    return rebuildMethodFromString();
  }

  public String toString()
  {
    return "ManifoldLightMethodBuilder: " + getName();
  }

  @Override
  public PsiElement setName( @NotNull String name ) throws IncorrectOperationException
  {
    return _nameIdentifier = new LightIdentifier( getManager(), name );
  }

  @Override
  public PsiElement replace( PsiElement newElement ) throws IncorrectOperationException
  {
    // just add new element to the containing class
    final PsiClass containingClass = getContainingClass();
    if( null != containingClass )
    {
      CheckUtil.checkWritable( containingClass );
      return containingClass.add( newElement );
    }
    return null;
  }

  @Override
  public void delete() throws IncorrectOperationException
  {
  }

  @Override
  public void checkDelete() throws IncorrectOperationException
  {
  }
}
