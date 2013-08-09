package be.mindworx.maven.plugin.adf;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.WriterFactory;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.deployment.DeployRequest;
import org.sonatype.aether.deployment.DeploymentException;
import org.sonatype.aether.impl.Deployer;
import org.sonatype.aether.repository.Authentication;
import org.sonatype.aether.repository.RemoteRepository;

import com.googlecode.mavenadf.JarDef;
import com.googlecode.mavenadf.JarLibrary;
import com.googlecode.mavenadf.JarLoader;


/**
 * Scans a JDeveloper installation folder for ADF libraries and deploys them to
 * a repository.
 * 
 * @goal deploy-adf
 * @phase deploy
 * @inheritByDefault true
 */
public class DeployADFLibrariesMojo extends AbstractMojo {

	/**
	 * The default Maven project created when building the plugin
	 * 
	 * @parameter default-value="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/**
	 * The fixed version used for deploying the ADF libraries and jar files.
	 * 
	 * @parameter expression="${adfVersion}";
	 * @required
	 */
	private String adfVersion;

	/**
	 * JDeveloper home folder.
	 * 
	 * @parameter expression="${jdevHome}";
	 * @required
	 */
	private File jdevHome;

	/**
	 * Use classpath manifest for resolving dependent jar libraries.
	 * 
	 * @parameter default-value="true"
	 * @required
	 */
	private boolean useClasspathManifest;

	/**
	 * Type packaging type used when deploying adf library artifacts
	 * 
	 * @parameter default-value="pom"
	 * @required
	 */
	private String packagingType;

	/**
	 * Server Id to map on the &lt;id&gt; under &lt;server&gt; section of
	 * settings.xml In most cases, this parameter will be required for
	 * authentication.
	 * 
	 * @parameter expression="${repositoryId}" default-value="remote-repository"
	 * @required
	 */
	private String repositoryId;

	/**
	 * The type of remote repository layout to deploy to. Try <i>legacy</i> for
	 * a Maven 1.x-style repository layout.
	 * 
	 * @parameter expression="${repositoryLayout}" default-value="default"
	 */
	private String repositoryLayout;

	/**
	 * URL where the artifact will be deployed. <br/>
	 * ie ( file:///C:/m2-repo or scp://host.com/path/to/repo )
	 * 
	 * @parameter expression="${url}"
	 * @required
	 */
	private String url;

	/**
	 * The prefix used when generating the groupId for the artifact. i.e. (
	 * com.oracle.jdeveloper )
	 * 
	 * @parameter expression="${groupIdPrefix}"
	 *            default-value="com.oracle.jdeveloper"
	 * @required
	 */
	private String groupIdPrefix;

	/**
	 * Flag whether Maven is currently in online/offline mode.
	 * 
	 * @parameter default-value="${settings.offline}"
	 * @readonly
	 */
	private boolean offline;

	/**
	 * @component
	 */
	private Deployer deployer;

	/**
	 * @component
	 */
	private LegacySupport legacySupport;

	/**
	 * Component used to create an artifact.
	 * 
	 * @component
	 */
	private RepositorySystem repositorySystem;

	/**
	 * Map that contains the layouts.
	 * 
	 * @component role=
	 *            "org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout"
	 */
	private Map repositoryLayouts;

	/**
	 * @parameter default-value="${localRepository}"
	 * @required
	 * @readonly
	 */
	private ArtifactRepository localRepository;

	/**
	 * Parameter used to control how many times a failed deployment will be
	 * retried before giving up and failing. If a value outside the range 1-10
	 * is specified it will be pulled to the nearest value within the range
	 * 1-10.
	 * 
	 * @parameter expression="${retryFailedDeploymentCount}" default-value="1"
	 * @since 2.7
	 */
	private int retryFailedDeploymentCount;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		failIfOffline();

		RemoteRepository remoteRepository = getDeploymentRepository();

		File targetFolder = new File(project.getBuild().getDirectory(), "deploy-adf");
		JarLoader jarLoader = new JarLoader(targetFolder, jdevHome, adfVersion, groupIdPrefix, false, useClasspathManifest, packagingType);

		List<JarLibrary> libraries = jarLoader.getLibraries();
		deployJdevLibraries(libraries, remoteRepository);
		deployJdevLibraryJars(jarLoader.getJars(), remoteRepository);
		jarLoader.writeMavenDependencyManagementFile();
	}

	private void deployJdevLibraries(List<JarLibrary> jdevLibraries, RemoteRepository remoteRepository) throws MojoExecutionException {
		for (JarLibrary jdevLibrary : jdevLibraries) {
			// Create the artifact
			Artifact artifact = repositorySystem.createArtifact(jdevLibrary.getGroupId(), jdevLibrary.getArtifactId(), jdevLibrary.getVersion(),
					jdevLibrary.getPackaging());
			deployFile(remoteRepository, artifact, jdevLibrary.getPomFile());

		}
	}

	private void deployJdevLibraryJars(Set<JarDef> jars, RemoteRepository remoteRepository) throws MojoExecutionException {
		try {
			for (JarDef jar : jars) {
				if ((jar.getType() == JarDef.JAR || jar.getType() == JarDef.MANIFEST) && jar.exists() && !jar.getFilename().endsWith("-SNAPSHOT.jar")) {
					Artifact artifact = repositorySystem.createArtifact(jar.getGroupId(), jar.getArtifactId(), jar.getLibrary().getVersion(), "jar");
					Artifact pomArtifact = repositorySystem.createArtifact(jar.getGroupId(), jar.getArtifactId(), jar.getLibrary().getVersion(), "pom");

					//ArtifactMetadata metadata = new ProjectArtifactMetadata(artifact, generatePomFile(jar.getGroupId(), jar.getArtifactId(), jar.getLibrary()
					//		.getVersion()));
					//artifact.addMetadata(metadata);

					deployFile(remoteRepository, pomArtifact, generatePomFile(jar.getGroupId(), jar.getArtifactId(), jar.getLibrary().getVersion()));
					deployFile(remoteRepository, artifact, new File(jar.getFilename()));
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private void deployFile(RemoteRepository remoteRepository, Artifact artifact, File file) throws MojoExecutionException {
		try {
			// artifact.setRelease( true );
			deploy(file, artifact, remoteRepository, localRepository);
		} catch (DeploymentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private RemoteRepository getDeploymentRepository() throws MojoExecutionException {
		ArtifactRepositoryLayout layout = getLayout(repositoryLayout);

		ArtifactRepository deploymentRepository = repositorySystem.createArtifactRepository(repositoryId, url, layout, new ArtifactRepositoryPolicy(true,
				ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN), new ArtifactRepositoryPolicy(true,
				ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN));

		String protocol = deploymentRepository.getProtocol();

		if (StringUtils.isEmpty(protocol)) {
			throw new MojoExecutionException("No transfer protocol found.");
		}

		RepositorySystemSession repositorySystemSession = legacySupport.getRepositorySession();
		RemoteRepository remoteRepository = RepositoryUtils.toRepo(deploymentRepository);
		Authentication authentication = repositorySystemSession.getAuthenticationSelector().getAuthentication(remoteRepository);
		remoteRepository.setAuthentication(authentication);

		return remoteRepository;
	}

	private void failIfOffline() throws MojoFailureException {
		assertOnline();
	}

	private void assertOnline() throws MojoFailureException {
		if (offline) {
			throw new MojoFailureException("Cannot deploy artifacts when Maven is in offline mode");
		}
	}

	private ArtifactRepositoryLayout getLayout(String id) throws MojoExecutionException {
		ArtifactRepositoryLayout layout = (ArtifactRepositoryLayout) repositoryLayouts.get(id);

		if (layout == null) {
			throw new MojoExecutionException("Invalid repository layout: " + id);
		}

		return layout;
	}

	/**
	 * Deploy an artifact from a particular file.
	 * 
	 * @param source
	 *            the file to deploy
	 * @param artifact
	 *            the artifact definition
	 * @param deploymentRepository
	 *            the repository to deploy to
	 * @param localRepository
	 *            the local repository to install into
	 * @throws ArtifactDeploymentException
	 *             if an error occurred deploying the artifact
	 */
	protected void deploy(File source, Artifact artifact, RemoteRepository remoteRepository, ArtifactRepository localRepository) throws DeploymentException {
		int retryFailedDeploymentCount = Math.max(1, Math.min(10, this.retryFailedDeploymentCount));
		DeploymentException exception = null;
		for (int count = 0; count < retryFailedDeploymentCount; count++) {
			try {
				if (count > 0) {
					getLog().info("Retrying deployment attempt " + (count + 1) + " of " + retryFailedDeploymentCount);
				}
				DeployRequest deployRequest = new DeployRequest();
				org.sonatype.aether.artifact.Artifact artifact2 = RepositoryUtils.toArtifact(artifact);
				artifact2 = artifact2.setFile(source);
				deployRequest.addArtifact(artifact2);
				deployRequest.setRepository(remoteRepository);
				deployer.deploy(legacySupport.getRepositorySession(), deployRequest);
				exception = null;
				break;
			} catch (DeploymentException e) {
				if (count + 1 < retryFailedDeploymentCount) {
					getLog().warn("Encountered issue during deployment: " + e.getLocalizedMessage());
					getLog().debug(e);
				}
				if (exception == null) {
					exception = e;
				}
			}
		}
		if (exception != null) {
			throw exception;
		}
	}

	/**
	 * Generates a minimal POM from the user-supplied artifact information.
	 * 
	 * @return The path to the generated POM file, never <code>null</code>.
	 * @throws MojoExecutionException
	 *             If the generation failed.
	 */
	private File generatePomFile(String groupId, String artifactId, String version) throws MojoExecutionException {
		Model model = generateModel(groupId, artifactId, version);

		Writer fw = null;
		try {
			File tempFile = File.createTempFile("mvndeploy", ".pom");
			tempFile.deleteOnExit();

			fw = WriterFactory.newXmlWriter(tempFile);
			new MavenXpp3Writer().write(fw, model);

			return tempFile;
		} catch (IOException e) {
			throw new MojoExecutionException("Error writing temporary pom file: " + e.getMessage(), e);
		} finally {
			IOUtil.close(fw);
		}
	}

	/**
	 * Generates a minimal model from the user-supplied artifact information.
	 * 
	 * @return The generated model, never <code>null</code>.
	 */
	private Model generateModel(String groupId, String artifactId, String version) {
		Model model = new Model();

		model.setModelVersion("4.0.0");

		model.setGroupId(groupId);
		model.setArtifactId(artifactId);
		model.setVersion(version);
		model.setPackaging("jar");

		model.setDescription("JDeveloper imported jar.");

		return model;
	}
}
