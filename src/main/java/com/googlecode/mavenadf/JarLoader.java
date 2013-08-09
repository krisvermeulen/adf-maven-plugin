package com.googlecode.mavenadf;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.digester.Digester;
import org.xml.sax.SAXException;

/**
 * 
 * @author Krisje
 * 
 */
public class JarLoader {

	private static final String GROUP_ID_SUFFIX = ".library";
	private final File workFolder;
	private final String adfVersion;
	private final String groupIdPrefix;
	private final String packagingType;

	static File jdevHome;
	static boolean verbose;
	static boolean useClasspathManifest;

	private static File currentFile = null;

	private String id = null;
	private String version = null;

	private List<JarLibrary> libs = null;

	public JarLoader(File workFolder, File jdevHome, String adfVersion, String groupIdPrefix, boolean verbose, boolean useClasspathManifest, String packagingType) {
		this.workFolder = workFolder;
		this.adfVersion = adfVersion;
		this.groupIdPrefix = groupIdPrefix;
		this.packagingType = packagingType;
		JarLoader.jdevHome = jdevHome;
		JarLoader.verbose = verbose;
		JarLoader.useClasspathManifest = useClasspathManifest;
	}

	public List<JarLibrary> getLibraries() {
		if (libs == null) {
			readLibraries(jdevHome);
		}
		return libs;
	}

	public Set<JarDef> getJars() {
		TreeSet<JarDef> sortedJars = new TreeSet<JarDef>();

		for (JarLibrary lib : getLibraries()) {
			sortedJars.addAll(lib.getJars());
		}

		return sortedJars;
	}

	public File writeMavenDependencyManagementFile() {
		File xmlFile = new File(workFolder, "dependencyManagement.xml");
		if (xmlFile.exists()) {
			xmlFile.delete();
		}

		TreeSet<JarLibrary> sortedLibs = new TreeSet<JarLibrary>(getLibraries());
		TreeSet<JarDef> sortedJars = new TreeSet<JarDef>(getJars());
		
		FileWriter xml = null;
		try {
			xml = new FileWriter(xmlFile);
			xml.append("  <dependencyManagement>\n");
			xml.append("    <dependencies>\n");
			xml.append("      <!-- JDev libraries -->");
			for (JarLibrary jarLibrary : sortedLibs) {
				xml.append("      <dependency>\n");
				xml.append("        <groupId>" + jarLibrary.getGroupId() + "</groupId>\n");
				xml.append("        <artifactId>" + jarLibrary.getArtifactId() + "</artifactId>\n");
				xml.append("        <version>" + jarLibrary.getVersion() + "</version>\n");
				xml.append("        <type>" + jarLibrary.getPackaging() + "</type>\n");
				xml.append("	  </dependency>\n");
			}
			xml.append("      <!-- JDev library jars -->");
			for (JarDef libraryJar : sortedJars) {
				xml.append("      <dependency>\n");
				xml.append("        <groupId>" + libraryJar.getGroupId() + "</groupId>\n");
				xml.append("        <artifactId>" + libraryJar.getArtifactId() + "</artifactId>\n");
				xml.append("        <version>" + libraryJar.getLibrary().getVersion() + "</version>\n");
				xml.append("		<scope>provided</scope>\n");
				xml.append("	  </dependency>\n");
			}
			xml.append("    </dependencies>\n");
			xml.append("  </dependencyManagement>");
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				xml.flush();
				xml.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return xmlFile;
	}

	private File writeMavenLibraryPom(JarLibrary lib) {
		File pomDir = getPomDir();
		if (!pomDir.exists()) {
			pomDir.mkdirs();
		}

		File pomFile = new File(pomDir, lib.getArtifactId() + ".pom");
		FileWriter out = null;
		try {
			out = new FileWriter(pomFile);
			if (verbose) {
				System.out.println("Creating pom for " + lib.getName());
			}
			writePomBegin(lib, out);
			for (JarDef jar : lib.getJars()) {
				if ((jar.getType() == JarDef.JAR || jar.getType() == JarDef.MANIFEST)) {
					writeJarDep(lib, jar, out);
				} else {
					if (verbose) {
						System.out.println("Lib: " + lib.getName() + " Skipping: " + jar.getFilename());
					}
				}
			}
			writePomEnd(lib, out);
		} catch (IOException e) {
			System.err.println("Error creating: " + lib.getName());
			System.err.println(e.getMessage());
		} finally {
			try {
				out.flush();
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		return pomFile;
	}

	private void writePomBegin(JarLibrary lib, FileWriter out) throws IOException {
		String libPath = lib.getLibraryFile().getCanonicalPath().substring(jdevHome.getCanonicalPath().length() + 1);
		out.append("<project>\n");
		out.append("  <modelVersion>4.0.0</modelVersion>\n");
		out.append("  <groupId>" + lib.getGroupId() + "</groupId>\n");
		out.append("  <artifactId>" + lib.getArtifactId() + "</artifactId>\n");
		String deployedByDefault = lib.getDeployed();
		if (deployedByDefault == null) {
			deployedByDefault = "false";
		}
		out.append("  <!-- JDeveloper library name: '" + lib.getName() + "' -->\n");
		out.append("  <!-- Deployed by default: " + deployedByDefault + "-->\n");
		out.append("  <packaging>" + lib.getPackaging() + "</packaging>\n");
		out.append("  <version>" + lib.getVersion() + "</version>\n");
		out.append("  <!-- This library pom was generated from ${JDEVHOME}/" + libPath.replaceAll("\\\\", "/") + "!META-INF/extension.xml -->\n");
		out.append("  <!-- Extension ID: '" + lib.getExtensionId() + "' -->\n");
		out.append("  <!-- Extension Version: '" + lib.getExtensionVersion() + "' -->\n");
		out.append("  <name>" + lib.getName() + "</name>\n");
		out.append("  <dependencies>\n");
	}

	private void writeJarDep(JarLibrary lib, JarDef jar, FileWriter out) throws IOException {
		boolean exists = false;
		if (new File(jar.getFilename()).exists()) {
			exists = true;
		}
		if (exists) {
			out.append("    <dependency>\n");
			if (jar.getType() == JarDef.MANIFEST) {
				out.append("      <!-- This dependency is from a MANIFEST classpath reference -->\n");
			}
			out.append("      <groupId>" + jar.getGroupId() + "</groupId>\n");
			out.append("      <artifactId>" + jar.getArtifactId() + "</artifactId>\n");
			out.append("      <version>" + lib.getVersion() + "</version>\n");

                        /* https://code.google.com/p/maven-adf/
                        try {
                                String jdevHomePath = new File(props.get(JDEVHOME) + "/..").getCanonicalPath();
                                
                                String filePath = "${JDEVHOME}/../" + jarFile.getAbsolutePath().substring(jdevHomePath.length()+1);
                                
                                out.append("      <!-- File from: '" + filePath + "' -->\n");
                        } catch (Throwable t) {
                                System.err.println("Error generating file location for: " + jarFile.getAbsolutePath());
                        }
                        FileInputStream fis = null;
                        try {
                                fis = new FileInputStream(jarFile);
                                String md5 = DigestUtils.md5Hex(fis);
                                out.append("      <!-- MD5='" + md5 + "' -->\n");
                                fis.close();
                                fis = new FileInputStream(jarFile);
                                String sha1 = DigestUtils.sha1Hex(fis);
                                out.append("      <!-- SHA1='" + sha1 + "' -->\n");
                                
                                
                                
                        } finally {
                                if (fis != null) {
                                        try { fis.close(); } catch (IOException e) {}
                                }
                        }
						*/
			writeManifestAttributes(jar, out);

			out.append("    </dependency>\n");
		} else {
			if (verbose) {
				System.err.println("Jar not found for library " + lib.getName() + ": " + jar.getFilename());
			}
			if (jar.getType() == JarDef.MANIFEST) {
				out.append("    <!-- No jar file found, but dependency was found for " + jar.getArtifactId() + " -->\n");
				out.append("    <!--   This dependency is from a MANIFEST classpath reference -->\n");
			} else {
				out.append("    <!-- No jar file found, but dependency was found for " + jar.getArtifactId() + " -->\n");
			}
			out.append("    <!--\n");
			out.append("    <dependency>\n");
			out.append("      <groupId>" + jar.getGroupId() + "</groupId>\n");
			out.append("      <artifactId>" + jar.getArtifactId() + "</artifactId>\n");
			out.append("      <version>" + lib.getVersion() + "</version>\n");
			out.append("    </dependency>\n");
			out.append("    -->\n");
		}
	}

	private void writeManifestAttributes(JarDef jar, FileWriter out) throws IOException {
		Attributes manifestAttributes = jar.getManifestAttributes();

		if (manifestAttributes != null) {

			TreeSet<Object> sortedAttributes = new TreeSet<Object>(new Comparator<Object>() {

				@Override
				public int compare(Object o1, Object o2) {
					return o1.toString().compareTo(o2.toString());
				}
			});
			sortedAttributes.addAll(manifestAttributes.keySet());

			out.append("      <!-- Manifest Info: -->\n");
			for (Object key : sortedAttributes) {
				String value = manifestAttributes.get(key).toString();
				if (key.toString() != null && value != null && !"".equals(value.trim())) {
					out.append("      <!--   " + key.toString() + "=" + value + " -->\n");
				}
			}
		}
	}

	private void writePomEnd(JarLibrary lib, FileWriter out) throws IOException {
		out.append("  </dependencies>\n");
		out.append("</project>\n");
	}

	private File getPomDir() {
		return new File(workFolder, "poms");
	}

	private List<JarLibrary> getJarLibs() {
		if (libs == null) {
			libs = new ArrayList<JarLibrary>();
		}
		return libs;
	}

	private void readLibraries(File folder) {
		if (!folder.exists()) {
			System.err.println("Directory does not exist: " + folder);
		} else {
			File[] allFiles = folder.listFiles();
			if (allFiles == null) {
				throw new NullPointerException("Permissions problem accessing: " + folder.getAbsolutePath());
			} else {
				for (int i = 0; i < allFiles.length; i++) {
					File file = allFiles[i];
					if (file.isDirectory()) {
						readLibraries(file);
					} else {
						if (file.getName().endsWith("jar")) {
							if (verbose) {
								System.out.println("Processing: " + file.getAbsolutePath());
							}
							getJDevExtensionXml(file);

						}
					}
				}
			}
		}
	}

	private void getJDevExtensionXml(File file) {
		JarFile jarfile = null;
		setCurrentFile(file);
		try {
			jarfile = new JarFile(file);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.err.println("Not really a jar: " + file.getName());
			System.err.println(e.getMessage());
			return;
		}
		JarEntry jarEntry = jarfile.getJarEntry("META-INF/extension.xml");
		if (jarEntry == null) {
			if (verbose) {
				System.out.println("No extension.xml found for: " + file.getAbsolutePath());
			}
		} else {
			InputStream is;
			try {
				setId(null);
				setVersion(null);
				is = jarfile.getInputStream(jarEntry);
				Digester digester = new Digester();
				addRules(digester);
				digester.push(this);
				digester.parse(is);
				is.close();
			} catch (IOException e1) {
				e1.printStackTrace();
			} catch (SAXException e) {
				e.printStackTrace();
			}
		}
		return;
	}

	/*
	 * <extension id="oracle.adf.share.dt" version="11.1.1.5.37.60.13"
	 * esdk-version="1.0" rsbundle-class="oracle.adf.share.dt.res.Bundle"
	 * xmlns="http://jcp.org/jsr/198/extension-manifest"> ... <library
	 * name="JPS Designtime">
	 * <classpath>../../../oracle_common/modules/oracle.jps_11
	 * .1.1/jps-ee.jar</classpath> </library> ...
	 */

	private void addRules(Digester d) {
		// d.addBeanPropertySetter("extension/hooks/libraries");

		d.addSetProperties("*/extension", "id", "id");
		d.addSetProperties("*/extension", "version", "version");
		d.addSetProperties("*/ex:extension", "id", "id");
		d.addSetProperties("*/ex:extension", "version", "version");
		d.addObjectCreate("*/libraries/library", JarLibrary.class);
		d.addSetProperties("*/libraries/library");
		d.addCallMethod("*/libraries/library/classpath", "addJarFile", 0);
		d.addCallMethod("*/libraries/library/srcpath", "addSrcFile", 0);
		d.addCallMethod("*/libraries/library/docpath", "addDocFile", 0);
		d.addSetNext("*/libraries/library", "addLibrary");

	}

	public void addLibrary(JarLibrary lib) {
		getJarLibs().add(lib);
		lib.setGroupIdPrefix(groupIdPrefix);
		lib.setPackaging(packagingType);
		lib.setGroupId(GROUP_ID_SUFFIX);
		lib.setVersion(adfVersion);
		lib.setLibraryFile(getCurrentFile());
		lib.setExtensionVersion(getVersion());
		lib.setExtensionId(getId());
		lib.setPomFile(writeMavenLibraryPom(lib));
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getId() {
		return this.id;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getVersion() {
		return this.version;
	}

	public static File getCurrentFile() {
		return currentFile;
	}

	public void setCurrentFile(File file) {
		currentFile = file;
	}

}
