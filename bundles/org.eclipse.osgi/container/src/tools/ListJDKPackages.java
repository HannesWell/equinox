package tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ListJDKPackages {

	public static void main(String[] args) throws IOException {
		Path base = Path.of("C:\\Users\\Hannes\\Desktop\\old-jdks\\jdk1.2.2");
		Path path = base.resolve("jre/lib/rt.jar");
		try (ZipFile zip = new ZipFile(path.toFile())) {
			Set<String> names = zip.stream().filter(e -> !e.isDirectory()).map(ZipEntry::getName)
					.filter(n -> !n.equals(JarFile.MANIFEST_NAME)).map(n -> {
						int i = n.lastIndexOf('/');
						return n.substring(0, i);
					}).collect(Collectors.toSet());
			names.stream().map(n -> n.replace('/', '.')).sorted().forEach(n -> System.out.println(" " + n + ",\\"));
		}
	}

}
