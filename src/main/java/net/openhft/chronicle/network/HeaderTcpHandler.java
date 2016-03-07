package net.openhft.chronicle.network;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.network.api.TcpHandler;
import net.openhft.chronicle.network.api.session.SessionDetails;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * @author Rob Austin.
 */
public class HeaderTcpHandler<T extends NetworkContext> implements TcpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HeaderTcpHandler.class);
    public static final String HANDLER = "handler";
    private final TcpEventHandler handlerManager;
    private final Function<Object, TcpHandler> handlerFunction;
    private final NetworkContext nc;


    public HeaderTcpHandler(@NotNull final TcpEventHandler handlerManager,
                            @NotNull final Function<Object, TcpHandler> handlerFunction,
                            @NotNull final T nc) {
        this.handlerManager = handlerManager;
        this.handlerFunction = handlerFunction;
        this.nc = nc;
    }

    @Override
    public void process(@NotNull Bytes in, @NotNull Bytes out) {

        assert nc.wireType() != null;

        // the type of the header
        final Wire inWire = nc.wireType().apply(in);

        long start = in.readPosition();

        try (final DocumentContext dc = inWire.readingDocument()) {

            if (!dc.isPresent())
                return;

            if (!dc.isData())
                throw new IllegalStateException("expecting a header of type data.");

            if (YamlLogging.showServerReads)
                LOG.info("read:\n" + Wires.fromSizePrefixedBlobs(in, start));

            final TcpHandler handler;

            final long readPosition = inWire.bytes().readPosition();
            final ValueIn read = inWire.read(() -> HANDLER);

            final Object o;

            if (read.isTyped())
                o = read.typedMarshallable();
            else {
                inWire.bytes().readPosition(readPosition);
                o = toSessionDetails(inWire);
            }

            handler = handlerFunction.apply(o);

            if (handler instanceof NetworkContextManager)
                ((NetworkContextManager) handler).nc(nc);

            handlerManager.tcpHandler(handler);

        } catch (Exception e) {
            LOG.error("wirein=" + Wires.fromSizePrefixedBlobs(in), e);
        }


    }

    public static WriteMarshallable toHeader(final WriteMarshallable m) {
        return wire -> {
            try (final DocumentContext dc = wire.writingDocument(false)) {
                wire.write(() -> HANDLER).typedMarshallable(m);
            }
        };
    }


    @NotNull
    public SessionDetails toSessionDetails(Wire inWire) {
        VanillaSessionDetails sd = new VanillaSessionDetails();
        sd.readMarshallable(inWire);
        return sd;
    }

    @Override
    public void close() {
        this.nc.closeTask().close();
    }

}