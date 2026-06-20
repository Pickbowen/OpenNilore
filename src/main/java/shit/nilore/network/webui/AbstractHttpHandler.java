package shit.nilore.network.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public abstract class AbstractHttpHandler implements HttpHandler {

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            int status = this.handleRequest(exchange.getRequestBody(), buffer, exchange);
            if (buffer.size() == 0) {
                this.sendResponse(status, buffer, exchange);
            }
            if (!(this instanceof StaticFileHandler)
                    && exchange.getResponseHeaders().getFirst("Content-Type") == null) {
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            }
            exchange.sendResponseHeaders(status, buffer.size());
            if (buffer.size() != 0) {
                exchange.getResponseBody().write(buffer.toByteArray());
            }
        } catch (Throwable throwable) {
            int status = 500;
            buffer = new ByteArrayOutputStream();
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            OutputStreamWriter writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8);
            writer.append("<!DOCTYPE html>\r\n<html>\r\n<head>\r\n\t<title>Sorry!</title>\r\n\t<meta charset=\"utf-8\">\r\n</head>\r\n<body>\r\n\t<h1>Sorry!</h1>\r\n\tThis request could not be completed due to an unexpected exception.<br/>\r\n\tHere is stack trace:\r\n\t<pre>");
            throwable.printStackTrace(new PrintWriter(writer));
            writer.append("</pre>\r\n</body>\r\n</html>");
            writer.flush();
            exchange.sendResponseHeaders(status, buffer.size());
            exchange.getResponseBody().write(buffer.toByteArray());
        }
        exchange.close();
    }

    public abstract int handleRequest(InputStream in, OutputStream out, HttpExchange exchange) throws Throwable;

    public void sendResponse(int status, OutputStream out, HttpExchange exchange) throws IOException {
    }
}
