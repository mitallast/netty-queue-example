package org.mitallast.queue.transport;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import org.mitallast.queue.common.Immutable;
import org.mitallast.queue.common.component.AbstractComponent;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.transport.netty.codec.MessageTransportFrame;
import org.mitallast.queue.transport.netty.codec.TransportFrame;
import org.mitallast.queue.transport.netty.codec.TransportFrameType;

import java.util.function.BiConsumer;

public class TransportController extends AbstractComponent {

    private volatile ImmutableMap<Class, BiConsumer> handlerMap = ImmutableMap.of();

    @Inject
    public TransportController(Settings settings) {
        super(settings);
    }

    public synchronized <Message extends Streamable> void registerMessageHandler(Class requestClass, BiConsumer<TransportChannel, Message> handler) {
        handlerMap = Immutable.compose(handlerMap, requestClass, handler);
    }

    public void dispatch(TransportChannel channel, TransportFrame messageFrame) {
        if (messageFrame.type() == TransportFrameType.PING) {
            // channel.send(messageFrame);
        } else if (messageFrame.type() == TransportFrameType.MESSAGE) {
            dispatchMessage(channel, (MessageTransportFrame) messageFrame);
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchMessage(TransportChannel channel, MessageTransportFrame messageFrame) {
        Streamable message = messageFrame.message();
        BiConsumer handler = handlerMap.get(message.getClass());
        if (handler != null) {
            handler.accept(channel, message);
        } else {
            logger.error("handler not found, close channel");
            channel.close();
        }
    }
}
