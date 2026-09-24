package com.polarlab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Entry point: prepares workspace, bundled case pack and starts the Javalin review server. */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5357;
        String home = Paths.get("workspace-data").toAbsolutePath().toString();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--home" -> home = args[++i];
                default -> { }
            }
        }
        Path homePath = Path.of(home);
        Files.createDirectories(homePath);
        Path caseDir = CasePack.writeTo(homePath);
        LOG.info("offline case pack at {}", caseDir);

        Db db = Db.open(homePath.toFile());
        Store store = new Store(db, homePath.resolve("blobs"));
        Server server = new Server(store, homePath, caseDir);
        server.seedIfEmpty();
        server.start(host, port);
        LOG.info("polar Mueller review workbench: http://{}:{}", host, port);
    }
}
