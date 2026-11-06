import java.net.URI;
import java.net.URLConnection;
import java.nio.file.*;

/** 使用 JDK TLS，避免部分 Windows 环境 Schannel 无凭据导致 PowerShell 下载失败。 */
class Download {
    public static void main(String[] args) throws Exception {
        URLConnection connection=URI.create(args[0]).toURL().openConnection();
        connection.setConnectTimeout(20000);connection.setReadTimeout(60000);
        try(var stream=connection.getInputStream()) { Files.copy(stream,Path.of(args[1]),StandardCopyOption.REPLACE_EXISTING); }
        System.out.println("已下载 "+args[1]+"，字节数 "+Files.size(Path.of(args[1])));
    }
}
