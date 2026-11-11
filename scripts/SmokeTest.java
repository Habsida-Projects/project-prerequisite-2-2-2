import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

// Run with JDK 21: java scripts/SmokeTest.java <Tomcat home> [WAR path]
// Uses a real external Tomcat; no test libraries or installed Maven are needed.
class SmokeTest {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final String RUSSIAN = "Приложение Spring MVC запущено!";

    public static void main(String[] args) throws Exception {
        check(Runtime.version().feature() == 21, "Run this check with JDK 21");
        check(args.length >= 1 && args.length <= 2,
                "Usage: java scripts/SmokeTest.java <Tomcat home> [WAR path]");
        Path home = Path.of(args[0]).toAbsolutePath();
        Path war = Path.of(args.length == 2 ? args[1] : "target/spring-mvc.war").toAbsolutePath();
        verifyTomcat(home);
        verifyWar(war);

        Path target = Path.of("target").toAbsolutePath();
        Files.createDirectories(target);
        Path base = Files.createTempDirectory(target, "tomcat-smoke-");
        Path log = base.resolve("tomcat.log");
        System.out.println("Test server and logs: " + base);
        copyDirectory(home.resolve("conf"), base.resolve("conf"));
        for (String directory : List.of("logs", "temp", "work", "webapps")) {
            Files.createDirectories(base.resolve(directory));
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        Files.writeString(base.resolve("conf/server.xml"), """
                <Server port="-1">
                  <Service name="Catalina">
                    <Connector address="127.0.0.1" port="%d" protocol="HTTP/1.1" />
                    <Engine name="Catalina" defaultHost="localhost">
                      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="false" />
                    </Engine>
                  </Service>
                </Server>
                """.formatted(port));
        Files.copy(war, base.resolve("webapps/spring-mvc.war"));
        Files.copy(war, base.resolve("webapps/ROOT.war"));
        String address = "http://127.0.0.1:" + port;

        try {
            runServer(home, base, log, address, true);
            System.out.println("PASS: WAR, both context paths, UTF-8, Thymeleaf, 404s and template refresh");
            runServer(home, base, log, address, false);
            String serverLog = Files.readString(log);
            for (String error : List.of("ClassNotFoundException", "NoSuchMethodError", "LinkageError")) {
                check(!serverLog.contains(error), "Tomcat log contains " + error + "; see " + log);
            }
            check(!java.util.regex.Pattern.compile("(?m)^.*\\s(?:SEVERE|ERROR)\\s+\\[")
                    .matcher(serverLog).find(), "Tomcat logged a severe error; see " + log);
            // On Windows, rebinding can fail during TIME_WAIT after the listener has stopped.
            // Check that connections are refused instead of requiring immediate rebinding.
            try (Socket socket = new Socket()) {
                boolean refused = false;
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                } catch (ConnectException expected) {
                    refused = true;
                }
                check(refused, "Tomcat is still accepting connections on port " + port);
                System.out.println("PASS: restart, clean server log and stopped HTTP listener on port " + port);
            }
        } catch (Exception | AssertionError failure) {
            if (Files.exists(log)) {
                System.err.println(Files.readString(log));
            }
            throw failure;
        }
    }

    private static void verifyTomcat(Path home) throws IOException {
        try (ZipFile catalina = new ZipFile(home.resolve("lib/catalina.jar").toFile())) {
            Properties properties = new Properties();
            try (var input = catalina.getInputStream(
                    catalina.getEntry("org/apache/catalina/util/ServerInfo.properties"))) {
                properties.load(input);
            }
            check(properties.getProperty("server.number", "").startsWith("11."), "Use Tomcat 11");
            System.out.println("Server: " + properties.getProperty("server.info"));
        }
    }

    private static void verifyWar(Path war) throws IOException {
        try (ZipFile archive = new ZipFile(war.toFile())) {
            for (String entry : List.of("WEB-INF/classes/web/config/AppInit.class",
                    "WEB-INF/classes/web/config/WebConfig.class",
                    "WEB-INF/classes/web/controller/HelloController.class", "WEB-INF/pages/index.html")) {
                check(archive.getEntry(entry) != null, "WAR is missing " + entry);
            }
            try (DataInputStream input = new DataInputStream(archive.getInputStream(
                    archive.getEntry("WEB-INF/classes/web/config/AppInit.class")))) {
                check(input.readInt() == 0xCAFEBABE, "Invalid class file");
                input.readUnsignedShort();
                check(input.readUnsignedShort() == 65, "Expected Java 21 bytecode (class version 65)");
            }
            var libraries = archive.stream().map(entry -> entry.getName())
                    .filter(name -> name.startsWith("WEB-INF/lib/") && name.endsWith(".jar")).toList();
            check(libraries.stream().noneMatch(name -> name.contains("servlet-api")),
                    "Servlet API must be provided by Tomcat, not packaged in the WAR");
            check(libraries.stream().noneMatch(name -> name.contains("thymeleaf-spring5")),
                    "Legacy Thymeleaf integration found");
            check(libraries.stream().anyMatch(name -> name.contains("thymeleaf-spring6-3.1.")),
                    "Expected Thymeleaf Spring integration 3.1.x");
            var springVersions = new HashSet<String>();
            for (String library : libraries) {
                var matcher = java.util.regex.Pattern.compile(
                        "WEB-INF/lib/spring-(?:aop|beans|context|core|expression|web|webmvc)-(.+)\\.jar")
                        .matcher(library);
                if (matcher.matches()) {
                    springVersions.add(matcher.group(1));
                }
            }
            check(springVersions.size() == 1 && springVersions.iterator().next().startsWith("7.0."),
                    "Expected consistent Spring 7.0.x modules, found " + springVersions);
            check(libraries.stream().anyMatch(name -> name.contains("/spring-webmvc-7.0.")),
                    "Spring MVC 7.0.x is missing");
        }
    }

    private static void runServer(Path home, Path base, Path log, String address, boolean refresh)
            throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = home.resolve("bin/bootstrap.jar") + java.io.File.pathSeparator
                + home.resolve("bin/tomcat-juli.jar");
        Process process = new ProcessBuilder(javaExecutable.toString(),
                "-Duser.language=en", "-Duser.country=US",
                "-Dcatalina.home=" + home, "-Dcatalina.base=" + base,
                "-Djava.io.tmpdir=" + base.resolve("temp"),
                "-Djava.util.logging.config.file=" + base.resolve("conf/logging.properties"),
                "-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager",
                "-cp", classpath, "org.apache.catalina.startup.Bootstrap", "start")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            awaitReady(address + "/spring-mvc/", process);
            for (String context : List.of("/spring-mvc", "")) {
                var response = get(address + context + "/");
                check(response.statusCode() == 200, "Welcome must return 200: " + context);
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                check(contentType.contains("text/html") && contentType.contains("charset=utf-8"),
                        "Expected HTML / UTF-8, got " + contentType);
                for (String message : List.of("Congratulations!",
                        "You just launched a Spring MVC application :)", RUSSIAN)) {
                    check(response.body().contains(message), "Missing rendered message: " + message);
                }
                check(!response.body().contains("${") && !response.body().contains("th:each"),
                        "Thymeleaf did not process the template");
                check(get(address + context + "/WEB-INF/pages/index.html").statusCode() == 404,
                        "WEB-INF template must not be publicly accessible");
                check(get(address + context + "/unknown").statusCode() == 404,
                        "Unknown route must return 404");
            }
            if (refresh) {
                Path template = base.resolve("webapps/spring-mvc/WEB-INF/pages/index.html");
                String original = Files.readString(template);
                try {
                    Files.writeString(template, original.replace("</body>", "<p>Template refresh OK</p></body>"));
                    // Tomcat also caches web resources (default TTL: 5 seconds).
                    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    boolean updated = false;
                    while (System.nanoTime() < deadline) {
                        if (get(address + "/spring-mvc/").body().contains("Template refresh OK")) {
                            updated = true;
                            break;
                        }
                        Thread.sleep(250);
                    }
                    check(updated, "Template changes must be visible without restarting Tomcat");
                } finally {
                    Files.writeString(template, original);
                }
            }
        } finally {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                check(process.waitFor(5, TimeUnit.SECONDS), "Tomcat process did not stop");
            }
        }
    }

    private static void awaitReady(String url, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            check(process.isAlive(), "Tomcat exited before deployment completed");
            try {
                if (get(url).statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // Connection refused while Tomcat starts: retry within the deadline.
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Tomcat did not become ready within 60 seconds: " + url);
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path copy = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
