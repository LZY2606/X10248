package polopt;

import polopt.web.Server;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5357;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) {
                host = args[i + 1];
            }
            if ("--port".equals(args[i])) {
                port = Integer.parseInt(args[i + 1]);
            }
        }
        new Server(host, port).start();
    }
}
