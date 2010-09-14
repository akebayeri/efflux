/*
 * Copyright 2010 Bruno de Carvalho
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.factor45.efflux.session;

import org.factor45.efflux.logging.Logger;
import org.factor45.efflux.network.ControlHandler;
import org.factor45.efflux.network.ControlPacketDecoder;
import org.factor45.efflux.network.ControlPacketEncoder;
import org.factor45.efflux.network.DataHandler;
import org.factor45.efflux.network.DataPacketDecoder;
import org.factor45.efflux.network.DataPacketEncoder;
import org.factor45.efflux.packet.AbstractReportPacket;
import org.factor45.efflux.packet.AppDataPacket;
import org.factor45.efflux.packet.ByePacket;
import org.factor45.efflux.packet.CompoundControlPacket;
import org.factor45.efflux.packet.ControlPacket;
import org.factor45.efflux.packet.DataPacket;
import org.factor45.efflux.packet.ReceiverReportPacket;
import org.factor45.efflux.packet.ReceptionReport;
import org.factor45.efflux.packet.SdesChunk;
import org.factor45.efflux.packet.SdesChunkItems;
import org.factor45.efflux.packet.SenderReportPacket;
import org.factor45.efflux.packet.SourceDescriptionPacket;
import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.FixedReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.socket.DatagramChannel;
import org.jboss.netty.channel.socket.DatagramChannelFactory;
import org.jboss.netty.channel.socket.nio.NioDatagramChannelFactory;
import org.jboss.netty.channel.socket.oio.OioDatagramChannelFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author <a:mailto="bruno.carvalho@wit-software.com" />Bruno de Carvalho</a>
 */
public abstract class AbstractRtpSession implements RtpSession {

    // constants ------------------------------------------------------------------------------------------------------

    protected static final Logger LOG = Logger.getLogger(SingleParticipantSession.class);
    protected static final String VERSION = "efflux_0.3_14092010";

    // configuration defaults -----------------------------------------------------------------------------------------

    // TODO not working with USE_NIO = false
    protected static final boolean USE_NIO = true;
    protected static final boolean DISCARD_OUT_OF_ORDER = true;
    protected static final int SEND_BUFFER_SIZE = 1500;
    protected static final int RECEIVE_BUFFER_SIZE = 1500;
    protected static final int MAX_COLLISIONS_BEFORE_CONSIDERING_LOOP = 3;
    protected static final boolean AUTOMATED_RTCP_HANDLING = true;

    // configuration --------------------------------------------------------------------------------------------------

    protected final String id;
    protected final int payloadType;
    protected String host;
    protected boolean discardOutOfOrder;
    protected int sendBufferSize;
    protected int receiveBufferSize;
    protected int maxCollisionsBeforeConsideringLoop;
    protected boolean automatedRtcpHandling;

    // internal vars --------------------------------------------------------------------------------------------------

    protected final RtpParticipant localParticipant;
    protected final Map<Long, DefaultRtpParticipantContext> participantTable;
    protected final List<RtpSessionDataListener> dataListeners;
    protected final List<RtpSessionControlListener> controlListeners;
    protected final List<RtpSessionEventListener> eventListeners;
    protected boolean useNio;
    protected boolean running;
    protected ConnectionlessBootstrap dataBootstrap;
    protected ConnectionlessBootstrap controlBootstrap;
    protected DatagramChannel dataChannel;
    protected DatagramChannel controlChannel;
    protected final AtomicInteger sequence;
    protected final AtomicBoolean sentOrReceivedPackets;
    protected final ReentrantReadWriteLock lock;
    protected final AtomicInteger collisions;

    // constructors ---------------------------------------------------------------------------------------------------

    public AbstractRtpSession(String id, int payloadType, RtpParticipant local) {
        if ((payloadType < 0) || (payloadType > 127)) {
            throw new IllegalArgumentException("PayloadType must be in range [0;127]");
        }

        this.id = id;
        this.payloadType = payloadType;
        this.localParticipant = local;
        this.participantTable = new HashMap<Long, DefaultRtpParticipantContext>();

        this.dataListeners = new CopyOnWriteArrayList<RtpSessionDataListener>();
        this.controlListeners = new CopyOnWriteArrayList<RtpSessionControlListener>();
        this.eventListeners = new CopyOnWriteArrayList<RtpSessionEventListener>();
        this.sequence = new AtomicInteger(0);
        this.sentOrReceivedPackets = new AtomicBoolean(false);
        this.lock = new ReentrantReadWriteLock();
        this.collisions = new AtomicInteger(0);

        this.useNio = USE_NIO;
        this.discardOutOfOrder = DISCARD_OUT_OF_ORDER;
        this.sendBufferSize = SEND_BUFFER_SIZE;
        this.receiveBufferSize = RECEIVE_BUFFER_SIZE;
        this.maxCollisionsBeforeConsideringLoop = MAX_COLLISIONS_BEFORE_CONSIDERING_LOOP;
        this.automatedRtcpHandling = AUTOMATED_RTCP_HANDLING;
    }

    // RtpSession -----------------------------------------------------------------------------------------------------

    @Override
    public String getId() {
        return id;
    }

    @Override
    public int getPayloadType() {
        return this.payloadType;
    }

    @Override
    public synchronized boolean init() {
        DatagramChannelFactory factory;
        if (this.useNio) {
            factory = new OioDatagramChannelFactory(Executors.newCachedThreadPool());
        } else {
            factory = new NioDatagramChannelFactory(Executors.newCachedThreadPool());
        }

        this.dataBootstrap = new ConnectionlessBootstrap(factory);
        this.dataBootstrap.setOption("sendBufferSize", this.sendBufferSize);
        this.dataBootstrap.setOption("receiveBufferSize", this.receiveBufferSize);
        this.dataBootstrap.setOption("receiveBufferSizePredictorFactory",
                                     new FixedReceiveBufferSizePredictorFactory(this.receiveBufferSize));
        this.dataBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(new DataPacketDecoder(),
                                         DataPacketEncoder.getInstance(),
                                         new DataHandler(AbstractRtpSession.this));
            }
        });
        this.controlBootstrap = new ConnectionlessBootstrap(factory);
        this.controlBootstrap.setOption("sendBufferSize", this.sendBufferSize);
        this.controlBootstrap.setOption("receiveBufferSize", this.receiveBufferSize);
        this.controlBootstrap.setOption("receiveBufferSizePredictorFactory",
                                        new FixedReceiveBufferSizePredictorFactory(this.receiveBufferSize));
        this.controlBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(new ControlPacketDecoder(),
                                         ControlPacketEncoder.getInstance(),
                                         new ControlHandler(AbstractRtpSession.this));
            }
        });

        SocketAddress dataAddress = this.localParticipant.getDataAddress();
        SocketAddress controlAddress = this.localParticipant.getControlAddress();

        try {
            this.dataChannel = (DatagramChannel) this.dataBootstrap.bind(dataAddress);
        } catch (Exception e) {
            LOG.error("Failed to bind data channel for session with id " + this.id, e);
            this.dataBootstrap.releaseExternalResources();
            this.controlBootstrap.releaseExternalResources();
            return false;
        }
        try {
            this.controlChannel = (DatagramChannel) this.controlBootstrap.bind(controlAddress);
        } catch (Exception e) {
            LOG.error("Failed to bind control channel for session with id " + this.id, e);
            this.dataChannel.close();
            this.dataBootstrap.releaseExternalResources();
            this.controlBootstrap.releaseExternalResources();
            return false;
        }

        LOG.debug("Data & Control channels bound for RtpSession with id {}.", this.id);
        // Send first RTCP packet.
        this.joinSession(this.localParticipant.getSsrc());
        return (this.running = true);
    }

    @Override
    public void terminate() {
        this.terminate(RtpSessionEventListener.TERMINATE_CALLED);
    }

    @Override
    public boolean sendData(byte[] data, long timestamp, boolean marked) {
        if (!this.running) {
            return false;
        }

        DataPacket packet = new DataPacket();
        // Other fields will be set by sendDataPacket()
        packet.setTimestamp(timestamp);
        packet.setData(data);
        packet.setMarker(marked);

        return this.sendDataPacket(packet);
    }

    @Override
    public boolean sendDataPacket(DataPacket packet) {
        if (!this.running) {
            return false;
        }

        packet.setPayloadType(this.payloadType);
        packet.setSsrc(this.localParticipant.getSsrc());
        packet.setSequenceNumber(this.sequence.incrementAndGet());
        return this.internalSendData(packet);
    }

    @Override
    public boolean sendControlPacket(ControlPacket packet) {
        // Only allow sending explicit RTCP packets if all the following conditions are met:
        // 1. session is running
        // 2. automated rtcp handling is disabled (except for APP_DATA packets) 
        if (!this.running) {
            return false;
        }

        if (ControlPacket.Type.APP_DATA.equals(packet.getType())) {
            return this.internalSendControl(packet);
        }

        return !this.automatedRtcpHandling && this.internalSendControl(packet);
    }

    @Override
    public boolean sendControlPacket(CompoundControlPacket packet) {
        return this.running && !this.automatedRtcpHandling && this.internalSendControl(packet);
    }

    @Override
    public RtpParticipant getLocalParticipant() {
        return this.localParticipant;
    }

    @Override
    public boolean addParticipant(RtpParticipant remoteParticipant) {
        if (remoteParticipant.getSsrc() == this.localParticipant.getSsrc()) {
            return false;
        }

        this.lock.writeLock().lock();
        try {
            DefaultRtpParticipantContext context = this.participantTable.get(remoteParticipant.getSsrc());
            if (context == null) {
                context = new DefaultRtpParticipantContext(remoteParticipant);
                this.participantTable.put(remoteParticipant.getSsrc(), context);
                return true;
            }
            return false;
        } finally {
            this.lock.writeLock().unlock();
        }

    }

    @Override
    public RtpParticipantContext removeParticipant(long ssrc) {
        this.lock.writeLock().lock();
        try {
            DefaultRtpParticipantContext context = this.participantTable.remove(ssrc);
            if (context == null) {
                return null;
            }

            return context;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public RtpParticipantContext getRemoteParticipant(long ssrc) {
        this.lock.readLock().lock();
        try {
            DefaultRtpParticipantContext context = this.participantTable.get(ssrc);
            if (context == null) {
                return null;
            }

            return context;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public Collection<RtpParticipantContext> getRemoteParticipants() {
        this.lock.readLock().lock();
        try {
            return Collections.<RtpParticipantContext>unmodifiableCollection(this.participantTable.values());
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void addDataListener(RtpSessionDataListener listener) {
        this.dataListeners.add(listener);
    }

    @Override
    public void removeDataListener(RtpSessionDataListener listener) {
        this.dataListeners.remove(listener);
    }

    @Override
    public void addControlListener(RtpSessionControlListener listener) {
        this.controlListeners.add(listener);
    }

    @Override
    public void removeControlListener(RtpSessionControlListener listener) {
        this.controlListeners.remove(listener);
    }

    @Override
    public void addEventListener(RtpSessionEventListener listener) {
        this.eventListeners.add(listener);
    }

    @Override
    public void removeEventListener(RtpSessionEventListener listener) {
        this.eventListeners.remove(listener);
    }

    // DataPacketReceiver ---------------------------------------------------------------------------------------------

    @Override
    public void dataPacketReceived(SocketAddress origin, DataPacket packet) {
        if (!this.running) {
            return;
        }

        if (packet.getPayloadType() != this.payloadType) {
            // Silently discard packets of wrong payload.
            return;
        }

        if (packet.getSsrc() == this.localParticipant.getSsrc()) {
            // Sending data to ourselves? Consider this a loop and bail out!
            if (origin.equals(this.localParticipant.getDataAddress())) {
                this.terminate(new Throwable("Loop detected: session is directly receiving its own packets"));
                return;
            } else if (this.collisions.incrementAndGet() > this.maxCollisionsBeforeConsideringLoop) {
                this.terminate(new Throwable("Loop detected after " + this.collisions.get() + " SSRC collisions"));
                return;
            }

            long oldSsrc = this.localParticipant.getSsrc();
            long newSsrc = this.localParticipant.resolveSsrcConflict(packet.getSsrc());

            // A collision has been detected after packets were sent, resolve by updating the local SSRC and sending
            // a BYE RTCP packet for the old SSRC.
            // http://tools.ietf.org/html/rfc3550#section-8.2
            // If no packet was sent and this is the first being received then we can avoid collisions by switching
            // our own SSRC to something else (nothing else is required because the collision was prematurely detected
            // and avoided).
            // http://tools.ietf.org/html/rfc3550#section-8.1, last paragraph
            if (this.sentOrReceivedPackets.getAndSet(true)) {
                this.leaveSession(oldSsrc, "SSRC collision detected; rejoining with new SSRC.");
                this.joinSession(newSsrc);
            }

            LOG.warn("SSRC collision with remote end detected on session with id {}; updating SSRC from {} to {}.",
                     this.id, oldSsrc, newSsrc);
            for (RtpSessionEventListener listener : this.eventListeners) {
                listener.resolvedSsrcConflict(this, oldSsrc, newSsrc);
            }
        }

        // Associate the packet with a participant or create one.
        DefaultRtpParticipantContext context = this.getOrCreateContextFromDataPacket(origin, packet);
        if (context == null) {
            // Subclasses may chose not to create anything, in which case this packet must be discarded.
            return;
        }

        // Should the packet be discarded due to out of order SN?
        if ((context.getLastSequenceNumber() >= packet.getSequenceNumber()) && this.discardOutOfOrder) {
            LOG.trace("Discarded out of order packet (last SN was {}, packet SN was {}).",
                      context.getLastSequenceNumber(), packet.getSequenceNumber());
            return;
        }

        // Update last SN and location for participant.
        // We trust the SSRC rather than the ip/port to identify participants (mostly because of NAT).
        context.setLastSequenceNumber(packet.getSequenceNumber());
        if (!origin.equals(context.getParticipant().getDataAddress())) {
            // I know. RFC mandates against this. Experience, however, tells me otherwise.
            context.getParticipant().updateDataAddress(origin);
            LOG.debug("Updated RTP address for {} to {} (session id: {}).", context.getParticipant(), origin, this.id);
        }

        // Finally, dispatch the event to the data listeners.
        for (RtpSessionDataListener listener : this.dataListeners) {
            listener.dataPacketReceived(this, context.getParticipant(), packet);
        }
    }

    // ControlPacketReceiver ------------------------------------------------------------------------------------------

    @Override
    public void controlPacketReceived(SocketAddress origin, CompoundControlPacket packet) {
        if (!this.running) {
            return;
        }

        if (!this.automatedRtcpHandling) {
            for (RtpSessionControlListener listener : this.controlListeners) {
                listener.controlPacketReceived(this, packet);
            }

            return;
        }

        for (ControlPacket controlPacket : packet.getControlPackets()) {
            switch (controlPacket.getType()) {
                case SENDER_REPORT:
                case RECEIVER_REPORT:
                    this.handleReportPacket(origin, (AbstractReportPacket) controlPacket);
                    break;
                case SOURCE_DESCRIPTION:
                    this.handleSdesPacket(origin, (SourceDescriptionPacket) controlPacket);
                    break;
                case BYE:
                    this.handleByePacket(origin, (ByePacket) controlPacket);
                    break;
                case APP_DATA:
                    for (RtpSessionControlListener listener : this.controlListeners) {
                        listener.appDataReceived(this, (AppDataPacket) controlPacket);
                    }
                default:
                    // do nothing, unknown case
            }
        }
    }

    protected void handleReportPacket(SocketAddress origin, AbstractReportPacket abstractReportPacket) {
        if (abstractReportPacket.getReceptionReportCount() == 0) {
            return;
        }

        DefaultRtpParticipantContext context = this.getExistingContext(abstractReportPacket.getSenderSsrc());
        if (context == null) {
            // Ignore; RTCP-SDES or RTP packet must first be received.
            return;
        }

        for (ReceptionReport receptionReport : abstractReportPacket.getReceptionReports()) {
            // Ignore all reception reports except for the one who pertains to the local participant (only data that
            // matters here is the link between this participant and ourselves).
            if (receptionReport.getSsrc() == this.localParticipant.getSsrc()) {
                // TODO
            }
        }

        // For sender reports, also handle the sender information.
        if (abstractReportPacket.getType().equals(ControlPacket.Type.SENDER_REPORT)) {
            SenderReportPacket senderReport = (SenderReportPacket) abstractReportPacket;
            // TODO
        }
    }

    protected void handleSdesPacket(SocketAddress origin, SourceDescriptionPacket packet) {
        for (SdesChunk chunk : packet.getChunks()) {
            DefaultRtpParticipantContext context = this.getOrCreateContextFromSdesChunk(origin, chunk);
            if (context == null) {
                continue;
            }
            if (!context.hasReceivedSdes()) {
                // If this participant wasn't created from an SDES packet, then update its participant's description.
                if (context.getParticipant().updateFromSdesChunk(chunk)) {
                    for (RtpSessionEventListener listener : this.eventListeners) {
                        listener.participantDataUpdated(this, context.getParticipant());
                    }
                }
            }
            // I know. RFC mandates against this. Experience, however, tells me otherwise.
            if (!origin.equals(context.getParticipant().getControlAddress())) {
                context.getParticipant().updateControlAddress(origin);
                LOG.debug("Updated RTCP address for {} to {} (session id: {}).",
                          context.getParticipant(), origin, this.id);
            }
        }
    }

    protected void handleByePacket(SocketAddress origin, ByePacket packet) {
        for (Long ssrc : packet.getSsrcList()) {
            DefaultRtpParticipantContext context = this.getExistingContext(ssrc);
            if (context != null) {
                context.byeReceived();
                for (RtpSessionEventListener listener : eventListeners) {
                    listener.participantLeft(this, context.getParticipant());
                }
            }
        }
        LOG.trace("Participants with SSRCs {} sent BYE for RtpSession with id {} with reason '{}'.",
                  packet.getSsrcList(), packet. getReasonForLeaving());
    }

    // protected helpers ----------------------------------------------------------------------------------------------

    protected boolean internalSendData(DataPacket packet) {
        this.lock.readLock().lock();
        try {
            for (RtpParticipantContext context : this.participantTable.values()) {
                if (context.receivedBye()) {
                    continue;
                }
                this.writeToData(packet, context.getParticipant().getDataAddress());
            }
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send RTP packet to participants in session with id {}.", this.id);
            return false;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    protected boolean internalSendControl(ControlPacket packet) {
        this.lock.readLock().lock();
        try {
            for (RtpParticipantContext context : this.participantTable.values()) {
                if (context.receivedBye()) {
                    continue;
                }
                this.writeToControl(packet, context.getParticipant().getControlAddress());
            }
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send RTCP packet to participants in session with id {}.", this.id);
            return false;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    protected boolean internalSendControl(CompoundControlPacket packet) {
        this.lock.readLock().lock();
        try {
            for (RtpParticipantContext context : this.participantTable.values()) {
                if (context.receivedBye()) {
                    continue;
                }
                this.writeToControl(packet, context.getParticipant().getControlAddress());
            }
            return true;
        } catch (Exception e) {
            LOG.error("Failed to send RTCP compound packet to participants in session with id {}.", this.id);
            return false;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    protected DefaultRtpParticipantContext getOrCreateContextFromDataPacket(SocketAddress origin, DataPacket packet) {
        // Get or create.
        this.lock.writeLock().lock();
        try {
            DefaultRtpParticipantContext context = this.participantTable.get(packet.getSsrc());
            if (context == null) {
                // New participant
                RtpParticipant participant = RtpParticipant
                        .createFromUnexpectedDataPacket((InetSocketAddress) origin, packet);
                context = new DefaultRtpParticipantContext(participant);
                this.participantTable.put(participant.getSsrc(), context);

                LOG.debug("New participant joined session with id {} (from data packet): {}.", this.id, participant);
                for (RtpSessionEventListener listener : this.eventListeners) {
                    listener.participantJoinedFromData(this, participant, packet);
                }
            }

            return context;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    protected DefaultRtpParticipantContext getOrCreateContextFromSdesChunk(SocketAddress origin, SdesChunk chunk) {
        this.lock.writeLock().lock();
        try {
            DefaultRtpParticipantContext context = this.participantTable.get(chunk.getSsrc());
            if (context == null) {
                RtpParticipant participant = RtpParticipant.createFromSdesChunk((InetSocketAddress) origin, chunk);
                context = new DefaultRtpParticipantContext(participant);
                // Mark SDES packet as received, in order not to update SDES info in the future.
                context.receivedSdes();
                this.participantTable.put(participant.getSsrc(), context);

                LOG.debug("New participant joined session with id {} (from SDES chunk): {}.", this.id, participant);
                for (RtpSessionEventListener listener : this.eventListeners) {
                    listener.participantJoinedFromControl(this, participant, chunk);
                }
            }

            return context;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    protected DefaultRtpParticipantContext getExistingContext(long ssrc) {
        return this.participantTable.get(ssrc);
    }

    protected void writeToData(DataPacket packet, SocketAddress destination) {
        this.dataChannel.write(packet, destination);
    }

    protected void writeToControl(ControlPacket packet, SocketAddress destination) {
        this.controlChannel.write(packet, destination);
    }

    protected void writeToControl(CompoundControlPacket packet, SocketAddress destination) {
        this.controlChannel.write(packet, destination);
    }

    protected void joinSession(long currentSsrc) {
        if (!this.automatedRtcpHandling) {
            return;
        }
        // Joining a session, so send an empty receiver report.
        ReceiverReportPacket emptyReceiverReport = new ReceiverReportPacket();
        emptyReceiverReport.setSenderSsrc(currentSsrc);
        // Send also an SDES packet in the compound RTCP packet.
        SourceDescriptionPacket sdesPacket = this.buildSdesPacket(currentSsrc);

        CompoundControlPacket compoundPacket = new CompoundControlPacket(emptyReceiverReport, sdesPacket);
        this.internalSendControl(compoundPacket);
    }

    protected void leaveSession(long currentSsrc, String motive) {
        if (!this.automatedRtcpHandling) {
            return;
        }
        List<CompoundControlPacket> byePackets = this.buildLeavePackets(currentSsrc, motive);
        for (CompoundControlPacket byePacket : byePackets) {
            this.internalSendControl(byePacket);
        }
    }

    protected List<CompoundControlPacket> buildLeavePackets(long currentSsrc, String motive) {
        SourceDescriptionPacket sdesPacket = this.buildSdesPacket(currentSsrc);
        ByePacket byePacket = new ByePacket();
        byePacket.addSsrc(currentSsrc);
        byePacket.setReasonForLeaving(motive);

        this.lock.readLock().lock();
        try {
            // Strong estimate.
            int participantCount = this.participantTable.size();
            List<CompoundControlPacket> compoundPackets = new ArrayList<CompoundControlPacket>(participantCount);
            for (DefaultRtpParticipantContext context : this.participantTable.values()) {
                AbstractReportPacket reportPacket = this.buildReportPacket(currentSsrc, context);
                compoundPackets.add(new CompoundControlPacket(reportPacket, sdesPacket, byePacket));
            }

            return compoundPackets;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private AbstractReportPacket buildReportPacket(long currentSsrc, DefaultRtpParticipantContext context) {
        AbstractReportPacket packet;
        if (context.getSentPackets() == 0) {
            // If no packets were sent to this source, then send a receiver report.
            packet = new ReceiverReportPacket();
        } else {
            // Otherwise, build a sender report.
            SenderReportPacket senderPacket = new SenderReportPacket();
            senderPacket.setNtpTimestamp(0); // FIXME
            senderPacket.setRtpTimestamp(System.currentTimeMillis()); // FIXME
            senderPacket.setSenderPacketCount(context.getSentPackets());
            senderPacket.setSenderOctetCount(context.getSentBytes());
            context.resetSendStats();
            packet = senderPacket;
        }
        packet.setSenderSsrc(currentSsrc);

        // If this source sent data, then calculate the link quality to build a reception report block.
        if (context.getReceivedPackets() > 0) {
            ReceptionReport block = new ReceptionReport();
            block.setSsrc(context.getParticipant().getSsrc());
            block.setDelaySinceLastSenderReport(0); // FIXME
            block.setFractionLost((short) 0); // FIXME
            block.setExtendedHighestSequenceNumberReceived(0); // FIXME
            block.setInterArrivalJitter(0); // FIXME
            block.setCumulativeNumberOfPacketsLost(0); // FIXME
            packet.addReceptionReportBlock(block);
        }

        return packet;
    }

    protected SourceDescriptionPacket buildSdesPacket(long currentSsrc) {
        SourceDescriptionPacket sdesPacket = new SourceDescriptionPacket();
        SdesChunk chunk = new SdesChunk(currentSsrc);

        if (this.localParticipant.getCname() == null) {
            this.localParticipant.setCname(new StringBuilder()
                    .append("efflux/").append(this.id).append('@')
                    .append(this.dataChannel.getLocalAddress()).toString());
        }
        chunk.addItem(SdesChunkItems.createCnameItem(this.localParticipant.getCname()));

        if (this.localParticipant.getName() != null) {
            chunk.addItem(SdesChunkItems.createNameItem(this.localParticipant.getName()));
        }

        if (this.localParticipant.getEmail() != null) {
            chunk.addItem(SdesChunkItems.createEmailItem(this.localParticipant.getEmail()));
        }

        if (this.localParticipant.getPhone() != null) {
            chunk.addItem(SdesChunkItems.createPhoneItem(this.localParticipant.getPhone()));
        }

        if (this.localParticipant.getLocation() != null) {
            chunk.addItem(SdesChunkItems.createLocationItem(this.localParticipant.getLocation()));
        }

        if (this.localParticipant.getTool() == null) {
            this.localParticipant.setTool(VERSION);
        }
        chunk.addItem(SdesChunkItems.createToolItem(this.localParticipant.getTool()));

        if (this.localParticipant.getNote() != null) {
            chunk.addItem(SdesChunkItems.createLocationItem(this.localParticipant.getNote()));
        }
        sdesPacket.addItem(chunk);

        return sdesPacket;
    }

    protected synchronized void terminate(Throwable cause) {
        if (!this.running) {
            return;
        }

        this.dataListeners.clear();
        this.controlListeners.clear();

        // Close data channel, send BYE RTCP packets and close control channel.
        this.dataChannel.close();
        this.leaveSession(this.localParticipant.getSsrc(), "Session terminated.");
        this.controlChannel.close();

        this.dataBootstrap.releaseExternalResources();
        this.controlBootstrap.releaseExternalResources();
        LOG.debug("RtpSession with id {} terminated.", this.id);

        for (RtpSessionEventListener listener : this.eventListeners) {
            listener.sessionTerminated(this, cause);
        }
        this.eventListeners.clear();

        this.running = false;
    }

    // getters & setters ----------------------------------------------------------------------------------------------

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        if (this.running) {
            throw new IllegalArgumentException("Cannot modify property after initialisation");
        }
        this.host = host;
    }

    public boolean useNio() {
        return useNio;
    }

    public void setUseNio(boolean useNio) {
        if (this.running) {
            throw new IllegalArgumentException("Cannot modify property after initialisation");
        }
        this.useNio = useNio;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isDiscardOutOfOrder() {
        return discardOutOfOrder;
    }

    public void setDiscardOutOfOrder(boolean discardOutOfOrder) {
        if (this.running) {
            throw new IllegalArgumentException("Cannot modify property after initialisation");
        }
        this.discardOutOfOrder = discardOutOfOrder;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public void setSendBufferSize(int sendBufferSize) {
        if (this.running) {
            throw new IllegalArgumentException("Cannot modify property after initialisation");
        }
        this.sendBufferSize = sendBufferSize;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public void setReceiveBufferSize(int receiveBufferSize) {
        if (this.running) {
            throw new IllegalArgumentException("Cannot modify property after initialisation");
        }
        this.receiveBufferSize = receiveBufferSize;
    }

    public int getMaxCollisionsBeforeConsideringLoop() {
        return maxCollisionsBeforeConsideringLoop;
    }

    public void setMaxCollisionsBeforeConsideringLoop(int maxCollisionsBeforeConsideringLoop) {
        if (this.running) {
            throw new IllegalArgumentException("Cannot modify property after initialisation");
        }
        this.maxCollisionsBeforeConsideringLoop = maxCollisionsBeforeConsideringLoop;
    }

    public boolean isAutomatedRtcpHandling() {
        return automatedRtcpHandling;
    }

    public void setAutomatedRtcpHandling(boolean automatedRtcpHandling) {
        if (this.running) {
            throw new IllegalArgumentException("Cannot modify property after initialisation");
        }
        this.automatedRtcpHandling = automatedRtcpHandling;
    }
}
