package com.googlecode.mavenadf;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;

/**
 * JarDef represents a jar definition as extracted from Jdeveloper Library
 * definitions.
 * 
 * Filename, as extracted, is the following format:
 * ../../{path}/{filename}.{ext}
 * 
 * The mapping to Maven repository becomes: GroupId com.oracle.jdeveloper.{path}
 * ArtifactId {filename} Version All jars are given the current IDE version
 * number.
 * 
 * Example: ../../BC4J/lib/adfshare.jar GroupId com.oracle.jdeveloper.BC4J.lib
 * ArtifactId adfshare Version 10.1.3.0.4
 */
public class JarDef implements Comparable<JarDef> {

	private JarLibrary library;
	private String filename;
	private int type;
	private Attributes manifestAttributes;
	private boolean exists = false;

	public static int JAR = 0;
	public static int SRC = 1;
	public static int DOC = 2;
	public static int MANIFEST = 3;

	public JarDef(JarLibrary library, String path, int type) {
		this.setLibrary(library);
		this.setFilename(path);
		this.setType(type);
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String newfilename) {
		boolean override = false;
		if ("${jdbc.library}".equals(newfilename)) {
			this.filename = "../../../wlserver_10.3/server/lib/ojdbc6.jar";
			override = true;
		} else if ("${orai18n.library}".equals(newfilename)) {
			this.filename = "../../../oracle_common/modules/oracle.nlsrtl_11.1.0/orai18n.jar";
			override = true;
		} else if (newfilename.contains("${ide.extension.install.home}")) {
			String path = JarLoader.getCurrentFile().getAbsolutePath();
			path = path.substring(0, path.lastIndexOf('.'));
			path = path.replace(JarLoader.jdevHome.getAbsolutePath(), ".");
			this.filename = newfilename.replace("${ide.extension.install.home}", path);
			override = true;
		} else if (newfilename.startsWith("./") || newfilename.startsWith(".\\")) {
			this.filename = newfilename.substring(2);
		} else {
			this.filename = newfilename;
		}

		File file = new File(getPathAndFilename());
		if (!file.exists()) {
			file = new File(JarLoader.jdevHome, getPathAndFilename());
		}
		if (!file.exists()) {
			file = new File(JarLoader.jdevHome, File.separator + ".." + File.separator + getPathAndFilename());
		}
		if (file.exists() && file.isFile()) {
			setExists(true);
			try {
				this.filename = file.getCanonicalPath();
				this.filename = this.filename.replaceAll("\\\\", "/");
			} catch (IOException e) {
				System.err.println("Cannot find canonical path of: " + file.getPath());
			}
			if (override && JarLoader.verbose) {
				System.out.println("Overriding symbolic " + newfilename + " with: " + this.filename);
			}
		}

	}

	public int getType() {
		return type;
	}

	public void setType(int type) {
		this.type = type;
	}

	public String toString() {
		return getFilename();
	}

	public String getPathAndFilename() {
		if (getFilename().startsWith("../..")) {
			return getFilename().substring(6);
		} else {
			return getFilename();
		}
	}

	public String getGroupId() throws IOException {
		String path = getFilename();
		String middlewarehome = JarLoader.jdevHome.getParentFile().getCanonicalPath();
		middlewarehome = middlewarehome.replaceAll("\\\\", "/");
		if (path.contains(middlewarehome)) {
			path = path.substring(middlewarehome.length() + 1);
		}
		String[] paths = path.split("/");
		String groupId = null;
		if (paths.length >= 1) {
			groupId = paths[0];
			for (int i = 1; i < paths.length - 1; i++) {
				groupId += "." + paths[i].replace('.', '_');
			}
		} else {
			groupId = paths[0];
		}
		while (groupId.length() > 0 && groupId.charAt(0) == '.') {
			groupId = groupId.substring(1, groupId.length());
		}
		if (groupId.endsWith(".jar")) {
			groupId = groupId.substring(0, groupId.length() - 4);
		}
		while (groupId.contains("__")) {
			groupId = groupId.replace("__", "_");
		}
		if (groupId.endsWith("_")) {
			groupId = groupId.substring(0, groupId.length() - 1);
		}

		return library.getGroupIdPrefix() + ".jars." + groupId;
	}

	public String getArtifactId() {
		int lastSlash = getFilename().lastIndexOf("/") + 1;
		int lastDot = getFilename().lastIndexOf(".");
		try {
			return getFilename().substring(lastSlash, lastDot);
		} catch (StringIndexOutOfBoundsException e) {
			return getFilename();
		}
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((filename == null) ? 0 : filename.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final JarDef other = (JarDef) obj;
		if (filename == null) {
			if (other.filename != null)
				return false;
		} else if (!filename.equals(other.filename))
			return false;
		return true;
	}

	public JarLibrary getLibrary() {
		return library;
	}

	public void setLibrary(JarLibrary library) {
		this.library = library;
	}

	public boolean exists() {
		return exists;
	}

	public void setExists(boolean exists) {
		this.exists = exists;
	}

	public Attributes getManifestAttributes() {
		return manifestAttributes;
	}

	public void setManifestAttributes(Attributes manifestAttributes) {
		this.manifestAttributes = manifestAttributes;
	}

	public int compareTo(JarDef o) {
		return this.getFilename().compareTo(o.getFilename());
	}

}