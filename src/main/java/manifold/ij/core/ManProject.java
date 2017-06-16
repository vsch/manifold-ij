package manifold.ij.core;

import com.intellij.ProjectTopics;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.impl.PsiDocumentTransactionListener;
import com.intellij.util.messages.MessageBusConnection;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import manifold.api.fs.IDirectory;
import manifold.api.fs.IFileUtil;
import manifold.api.fs.jar.JarFileDirectoryImpl;
import manifold.api.host.Dependency;
import manifold.api.host.IModule;
import manifold.ij.extensions.FileModificationManager;
import manifold.ij.extensions.ModuleClasspathListener;
import manifold.ij.extensions.ModuleRefreshListener;
import manifold.ij.fs.IjFileSystem;
import manifold.util.concurrent.ConcurrentWeakHashMap;
import manifold.util.concurrent.LocklessLazyVar;

/**
 */
public class ManProject
{
  private static final Map<Project, ManProject> PROJECTS = new ConcurrentWeakHashMap<>();
  private static final String JAR_INDICATOR = ".jar!";

  private final Project _ijProject;
  private IjFileSystem _fs;
  private LocklessLazyVar<List<ManModule>> _modules;
  private MessageBusConnection _projectConnection;
  private MessageBusConnection _applicationConnection;
  private MessageBusConnection _permanentProjectConnection;
  private ModuleClasspathListener _moduleClasspathListener;
  private FileModificationManager _fileModificationManager;

  public static Collection<ManProject> getAllProjects()
  {
    return PROJECTS.values().stream().filter( p -> !p.getNativeProject().isDisposed() ).collect( Collectors.toSet() );
  }

  public static Project projectFrom( ManModule module )
  {
    return module.getIjProject();
  }

  public static ManProject manProjectFrom( Module module )
  {
    return manProjectFrom( module.getProject() );
  }

  public static ManProject manProjectFrom( Project project )
  {
    return getProject( project );
  }

  public static ManModule getModule( Module module )
  {
    ManProject manProject = getProject( module.getProject() );
    for( ManModule mm : manProject.getModules() )
    {
      if( mm.getIjModule().equals( module ) )
      {
        return mm;
      }
    }
    return null;
  }

  private static ManProject getProject( Project project )
  {
    if( project.isDisposed() )
    {
      return null;
    }

    ManProject manProject = PROJECTS.get( project );
    if( manProject == null )
    {
      manProject = new ManProject( project );
      manProject.init();
      PROJECTS.put( project, manProject );
    }
    return manProject;
  }

  public ManProject( Project project )
  {
    _ijProject = project;
  }

  private void init()
  {
    _fs = new IjFileSystem( this );
    _modules = LocklessLazyVar.make( () -> ApplicationManager.getApplication().<List<ManModule>>runReadAction( this::defineModules ) );
  }

  public void reset()
  {
    ApplicationManager.getApplication().runReadAction(
      () -> {
        init();
        _fileModificationManager.getManRefresher().nukeFromOrbit();
      } );
  }

  public IjFileSystem getFileSystem()
  {
    return _fs;
  }

  public Project getNativeProject()
  {
    return _ijProject;
  }

  public List<ManModule> getModules()
  {
    return _modules.get();
  }

  public void projectOpened()
  {
    _applicationConnection = ApplicationManager.getApplication().getMessageBus().connect();
    _projectConnection = _ijProject.getMessageBus().connect();
    _permanentProjectConnection = _ijProject.getMessageBus().connect();

    addTypeRefreshListener();
    addModuleRefreshListener();
    addModuleClasspathListener();
  }

  public ModuleClasspathListener getModuleClasspathListener()
  {
    return _moduleClasspathListener;
  }

  private void addModuleClasspathListener()
  {
    _moduleClasspathListener = new ModuleClasspathListener();
    _permanentProjectConnection.subscribe( ProjectTopics.PROJECT_ROOTS, _moduleClasspathListener );
  }

  public void projectClosed()
  {
    _projectConnection.disconnect();
    _projectConnection = null;
    PROJECTS.remove( getNativeProject() );
    _fileModificationManager.getManRefresher().nukeFromOrbit();
  }

  private void addTypeRefreshListener() {
    _fileModificationManager = new FileModificationManager( this );
    _projectConnection.subscribe( PsiDocumentTransactionListener.TOPIC, _fileModificationManager );
    _applicationConnection.subscribe( VirtualFileManager.VFS_CHANGES, _fileModificationManager );
  }

  private void addModuleRefreshListener()
  {
    ModuleRefreshListener moduleRefreshListener = new ModuleRefreshListener();
    _projectConnection.subscribe( ProjectTopics.MODULES, moduleRefreshListener );
  }

  public FileModificationManager getFileModificationManager()
  {
    return _fileModificationManager;
  }

  public List<ManModule> findRootModules()
  {
    List<ManModule> roots = new ArrayList<>( getModules() );
    for( ManModule module : roots )
    {
      for( Dependency d : module.getDependencies() )
      {
        roots.remove( d.getModule() );
      }
    }
    return roots;
  }

  private List<ManModule> defineModules()
  {
    ModuleManager moduleManager = ModuleManager.getInstance( _ijProject );
    Module[] allIjModules = moduleManager.getModules();

    List<IDirectory> allSourcePaths = new ArrayList<>();
    Map<Module, ManModule> modules = new HashMap<>();
    List<ManModule> allModules = new ArrayList<>();
    for( Module ijModule : allIjModules )
    {
      final ManModule module = defineModule( ijModule );
      if( module != null )
      {
        allSourcePaths.addAll( module.getSourcePath() );
        modules.put( ijModule, module );
        allModules.add( module );
      }
    }

    for( Module ijModule : allIjModules )
    {
      final ManModule module = modules.get( ijModule );
      for( Module child : ModuleRootManager.getInstance( ijModule ).getDependencies() )
      {
        IModule moduleDep = modules.get( child );
        if( moduleDep != null )
        {
          module.addDependency( new Dependency( moduleDep, isExported( ijModule, child ) ) );
        }
      }
    }

    return allModules;
  }

  public static boolean isExported( Module ijModule, Module child )
  {
    for( OrderEntry entry : ModuleRootManager.getInstance( ijModule ).getOrderEntries() )
    {
      if( entry instanceof ModuleOrderEntry )
      {
        final ModuleOrderEntry moduleEntry = (ModuleOrderEntry)entry;
        final DependencyScope scope = moduleEntry.getScope();
        if( !scope.isForProductionCompile() && !scope.isForProductionRuntime() )
        {
          continue;
        }
        final Module module = moduleEntry.getModule();
        if( module != null && module == child )
        {
          return moduleEntry.isExported();
        }
      }
    }
    return false;
  }

  ManModule defineModule( Module ijModule )
  {
    List<VirtualFile> sourceFolders = getSourceRoots( ijModule );
    VirtualFile outputPath = CompilerPaths.getModuleOutputDirectory( ijModule, false );
    return createModule( ijModule, getClassPaths( ijModule ),
                         sourceFolders.stream().map( this::toDirectory ).collect( Collectors.toList() ),
                         outputPath == null ? null : getFileSystem().getIDirectory( outputPath ) );
  }

  public ManModule createModule( Module ijModule, List<IDirectory> classpath, List<IDirectory> sourcePaths, IDirectory outputPath )
  {
    // Maybe expand paths to include Class-Path attribute from Manifest...
    classpath = addFromManifestClassPath( classpath );
    sourcePaths = addFromManifestClassPath( sourcePaths );

    // Scan....
    List<IDirectory> sourceRoots = new ArrayList<>( sourcePaths );
    scanPaths( classpath, sourceRoots );

    return new ManModule( this, ijModule, classpath, sourceRoots, outputPath, getExcludedFolders( ijModule ) );
  }

  private static void scanPaths( List<IDirectory> paths, List<IDirectory> roots )
  {
    //noinspection Convert2streamapi
    for( IDirectory root : paths )
    {
      // roots without manifests are considered source roots
      if( IFileUtil.hasSourceFiles( root ) )
      {
        if( !roots.contains( root ) )
        {
          roots.add( root );
        }
      }
    }
  }

  /**
   * <p>This will add items to the classpath, but only under very specific circumstances.
   * <p>If both of the following conditions are met:
   * <ul>
   * <li>The JAR's manifest contains a Class-Path entry</li>
   * <li>The Class-Path entry contains a space-delimited list of URIs</li>
   * </ul>
   * <p>Then the entries will be parsed and added to the classpath.
   * <p>
   * <p>This logic also handles strange libraries packaged pre-Maven such as xalan:xalan:2.4.1
   * <p>
   * <p>The xalan JAR above has a Class-Path attribute referencing the following:
   * <pre>
   *   Class-Path: xercesImpl.jar xml-apis.jar
   * </pre>
   * <p>
   * These unqualified references should have been resolved by the build tooling, and if we try to interfere and resolve
   * the references, we may cause classpath confusion. Therefore any Class-Path entry not resolvable to an absolute
   * path on disk (and, therefore, can be listed as a URL) will be skipped.
   *
   * @param classpath The module's Java classpath
   *
   * @return The original classpath, possibly with dependencies listed in JAR manifests Class-Path extracted and explicitly listed
   *
   * @see java.util.jar.Attributes.Name#CLASS_PATH
   */
  private List<IDirectory> addFromManifestClassPath( List<IDirectory> classpath )
  {
    if( classpath == null )
    {
      return classpath;
    }

    ArrayList<IDirectory> newClasspath = new ArrayList<>();
    for( IDirectory root : classpath )
    {
      //add the root JAR itself first, preserving ordering
      if( !newClasspath.contains( root ) )
      {
        newClasspath.add( root );
      }
      if( root instanceof JarFileDirectoryImpl )
      {
        JarFile jarFile = ((JarFileDirectoryImpl)root).getJarFile();
        try
        {
          Manifest manifest = jarFile.getManifest();
          if( manifest != null )
          {
            Attributes man = manifest.getMainAttributes();
            String paths = man.getValue( Attributes.Name.CLASS_PATH );
            if( paths != null && !paths.isEmpty() )
            {
              // We found a Jar with a Class-Path listing.
              // Note sometimes happens when running from IntelliJ where the
              // classpath would otherwise make the command line to java.exe
              // too long.
              for( String j : paths.split( " " ) )
              {
                // Add each of the paths to our classpath
                URL url;
                try
                {
                  url = new URL( j );
                }
                catch( MalformedURLException e )
                {
                  //Class-Path contained an invalid URL, skip it
                  continue;
                }
                File dirOrJar = new File( url.toURI() );
                IDirectory idir = getFileSystem().getIDirectory( dirOrJar );
                if( !newClasspath.contains( idir ) )
                {
                  newClasspath.add( idir );
                }
              }
            }
          }
        }
        catch( Exception e )
        {
          throw new RuntimeException( e );
        }
      }
    }

    return newClasspath;
  }

  private List<IDirectory> getExcludedFolders( Module ijModule )
  {
    return getExcludedRoots( ijModule ).stream().map( this::toDirectory ).collect( Collectors.toList() );
  }

  public static List<VirtualFile> getSourceRoots( Module ijModule )
  {
    final ModuleRootManager moduleManager = ModuleRootManager.getInstance( ijModule );
    final List<VirtualFile> sourcePaths = new ArrayList<>();
    List<VirtualFile> excludeRoots = Arrays.asList( moduleManager.getExcludeRoots() );
    for( VirtualFile sourceRoot : moduleManager.getSourceRoots() )
    {
      if( !excludeRoots.contains( sourceRoot ) )
      {
        sourcePaths.add( sourceRoot );
      }
    }

    return sourcePaths;
  }

  public static List<VirtualFile> getExcludedRoots( Module ijModule )
  {
    final ModuleRootManager moduleManager = ModuleRootManager.getInstance( ijModule );
    return Arrays.asList( moduleManager.getExcludeRoots() );
  }

  private IDirectory toDirectory( VirtualFile file )
  {
    String sourcePath = file.getPath();
    if( sourcePath.contains( JAR_INDICATOR ) )
    {
      sourcePath = sourcePath.substring( 0, sourcePath.length() - 2 );
    }
    return getFileSystem().getIDirectory( new File( sourcePath ) );
  }

  public static List<IDirectory> getClassPaths( Module ijModule )
  {
    List<String> paths = getDirectClassPaths( ijModule );
    for( Iterator<String> it = paths.iterator(); it.hasNext(); )
    {
      String url = it.next();
      if( dependencyChainContains( ijModule, url, new ArrayList<Module>() ) )
      {
        it.remove();
      }
    }
    List<IDirectory> dirs = new ArrayList<>();
    for( String path : paths )
    {
      dirs.add( manProjectFrom( ijModule ).getFileSystem().getIDirectory( new File( path ) ) );
    }
    return dirs;
  }

  private static List<String> getDirectClassPaths( Module ijModule )
  {
    final ModuleRootManager rootManager = ModuleRootManager.getInstance( ijModule );
    final List<OrderEntry> orderEntries = Arrays.asList( rootManager.getOrderEntries() );

    List<String> paths = new ArrayList<>();
    for( OrderEntry entry : orderEntries.stream().filter( (LibraryOrderEntry.class)::isInstance ).collect( Collectors.toList() ) )
    {
      final Library lib = ((LibraryOrderEntry)entry).getLibrary();
      if( lib != null )
      {
        for( VirtualFile virtualFile : lib.getFiles( OrderRootType.CLASSES ) )
        {
          final File file = new File( stripExtraCharacters( virtualFile.getPath() ) );
          if( file.exists() )
          {
            paths.add( file.getAbsolutePath() );
          }
        }
      }
    }
    return paths;
  }

  private static boolean dependencyChainContains( Module ijModule, String path, List<Module> visited )
  {
    if( !visited.contains( ijModule ) )
    {
      visited.add( ijModule );

      ModuleRootManager rootManager = ModuleRootManager.getInstance( ijModule );
      for( Module dep : rootManager.getDependencies() )
      {
        if( getDirectClassPaths( dep ).contains( path ) || dependencyChainContains( dep, path, visited ) )
        {
          return true;
        }
      }
    }
    return false;
  }

  private static String stripExtraCharacters( String fileName )
  {
    if( fileName.endsWith( "!/" ) )
    {
      fileName = fileName.substring( 0, fileName.length() - 2 );
    }
    return fileName;
  }
}
