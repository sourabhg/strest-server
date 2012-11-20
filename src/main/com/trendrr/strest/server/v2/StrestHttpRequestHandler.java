/**
 * 
 */
package com.trendrr.strest.server.v2;

import static org.jboss.netty.handler.codec.http.HttpHeaders.is100ContinueExpected;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

import static org.jboss.netty.handler.codec.http.HttpHeaders.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.jboss.netty.handler.codec.http.HttpMethod.*;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;
import static org.jboss.netty.handler.codec.http.HttpVersion.*;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.trendrr.oss.DynMap;
import com.trendrr.strest.server.StrestRouter;
import com.trendrr.strest.server.connections.StrestNettyConnectionChannel;
import com.trendrr.strest.server.v2.models.http.StrestHttpRequest;
import com.trendrr.strest.server.v2.models.json.StrestJsonRequest;


/**
 * @author Dustin Norlander
 * @created Nov 20, 2012
 * 
 */
public class StrestHttpRequestHandler  extends SimpleChannelUpstreamHandler {

	protected static Log log = LogFactory
			.getLog(StrestHttpRequestHandler.class);
	

	protected StrestRouter router;
	
	protected WebSocketServerHandshaker handshaker;
	
	//TODO: pull this from the router config.
	private static final String WEBSOCKET_PATH = "/websocket";
	
	public StrestHttpRequestHandler(StrestRouter r) {
		this.router = r;
	}
	
	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
//		log.info("Connect! " + ctx);
	}
	
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    	
    	Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
            handleHttpRequest(ctx, e);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, e);
        }
    	
    	
    	
    }
    
    protected void handleWebSocketFrame(ChannelHandlerContext ctx, MessageEvent e) throws Exception{
    	WebSocketFrame frame = (WebSocketFrame)e.getMessage();
        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.getChannel(), (CloseWebSocketFrame) frame);
            return;
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
            return;
        } else if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }

        // Send the uppercase string back.
        String json = ((TextWebSocketFrame) frame).getText();
        
        DynMap map = DynMap.instance(json);
        if (map.isEmpty()) {
        	throw new Exception("Invalid json");
        }
        
        StrestJsonRequest req = new StrestJsonRequest(map);
        req.setConnectionChannel(StrestNettyConnectionChannel.get(e.getChannel()));
        router.incoming(req);
        
        //TODO: this sure isn't right.
//        ctx.getChannel().write(new TextWebSocketFrame(json.toUpperCase()));
    }
    protected boolean isWebSocketUpgrade(HttpRequest request) {
    	String upgrade = request.getHeader("Upgrade");
    	return (upgrade != null && upgrade.equalsIgnoreCase("websocket"));
    }
    
    private String getWebSocketLocation(HttpRequest req) {
        return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + WEBSOCKET_PATH;
    }
    
    protected void handleHttpRequest(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    	HttpRequest request = (HttpRequest) e.getMessage();
        
    	//check if this is a Websocket upgrade request.
    	if (isWebSocketUpgrade(request)) {
    		// Handshake
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    getWebSocketLocation(request), null, false);
            handshaker = wsFactory.newHandshaker(request);
            if (handshaker == null) {
                wsFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel());
            } else {
                handshaker.handshake(ctx.getChannel(), request).addListener(
                		new ChannelFutureListener() {
                	        public void operationComplete(ChannelFuture future) throws Exception {
                	            if (!future.isSuccess()) {
                	                Channels.fireExceptionCaught(future.getChannel(), future.getCause());
                	                return;
                	            }
                	            //add the strest object encoder
                	            future.getChannel().getPipeline().addLast("jsonencoder", new StrestWebSocketEncoder());

//                	            System.out.println(future.getChannel().getPipeline().get("wsencoder"));
//                	            System.out.println(future.getChannel().getPipeline().getNames());
                	        }
                	    }
                );
                
            }
            return;
    	}
    	
    	//Now handle regular http requests.
        StrestHttpRequest req = new StrestHttpRequest(request);
        Channel channel = e.getChannel();
        StrestNettyConnectionChannel con = StrestNettyConnectionChannel.get(channel);
        req.setConnectionChannel(con);
        router.incoming(req);
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
    	StrestNettyConnectionChannel.remove(e.getChannel());
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
            throws Exception {
    	log.warn("Caught", e.getCause());
        e.getChannel().close();
        StrestNettyConnectionChannel.remove(e.getChannel());
    }
}