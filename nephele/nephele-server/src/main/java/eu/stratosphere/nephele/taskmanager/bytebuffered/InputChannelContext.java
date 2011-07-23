package eu.stratosphere.nephele.taskmanager.bytebuffered;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import eu.stratosphere.nephele.event.task.AbstractEvent;
import eu.stratosphere.nephele.event.task.EventList;
import eu.stratosphere.nephele.io.channels.Buffer;
import eu.stratosphere.nephele.io.channels.ChannelID;
import eu.stratosphere.nephele.io.channels.bytebuffered.AbstractByteBufferedInputChannel;
import eu.stratosphere.nephele.io.channels.bytebuffered.BufferPairResponse;
import eu.stratosphere.nephele.io.channels.bytebuffered.ByteBufferedChannelActivateEvent;
import eu.stratosphere.nephele.io.channels.bytebuffered.ByteBufferedInputChannelBroker;
import eu.stratosphere.nephele.jobgraph.JobID;
import eu.stratosphere.nephele.taskmanager.bufferprovider.BufferProvider;
import eu.stratosphere.nephele.taskmanager.transferenvelope.TransferEnvelope;
import eu.stratosphere.nephele.taskmanager.transferenvelope.TransferEnvelopeDispatcher;
import eu.stratosphere.nephele.util.StringUtils;

final class InputChannelContext implements ChannelContext, ByteBufferedInputChannelBroker, BufferProvider {

	private static final Log LOG = LogFactory.getLog(InputChannelContext.class);

	private final InputGateContext inputGateContext;

	private final AbstractByteBufferedInputChannel<?> byteBufferedInputChannel;

	private final TransferEnvelopeDispatcher transferEnvelopeDispatcher;

	private final Queue<TransferEnvelope> queuedEnvelopes = new ArrayDeque<TransferEnvelope>();

	InputChannelContext(final InputGateContext inputGateContext,
			final TransferEnvelopeDispatcher transferEnvelopeDispatcher,
			final AbstractByteBufferedInputChannel<?> byteBufferedInputChannel) {

		this.inputGateContext = inputGateContext;
		this.transferEnvelopeDispatcher = transferEnvelopeDispatcher;
		this.byteBufferedInputChannel = byteBufferedInputChannel;
		this.byteBufferedInputChannel.setInputChannelBroker(this);
		
		//Make sure the corresponding output channel is activated
		try {
			transferEventToOutputChannel(new ByteBufferedChannelActivateEvent());
		} catch(Exception e) {
			LOG.error(StringUtils.stringifyException(e));
		}
	}

	@Override
	public BufferPairResponse getReadBufferToConsume() {

		TransferEnvelope transferEnvelope = null;

		synchronized (this.queuedEnvelopes) {

			if (this.queuedEnvelopes.isEmpty()) {
				return null;
			}

			transferEnvelope = this.queuedEnvelopes.peek();

			// If envelope does not have a buffer, remove it immediately
			if (transferEnvelope.getBuffer() == null) {
				this.queuedEnvelopes.poll();
			}
		}

		// Make sure we have all necessary buffers before we go on
		if (transferEnvelope.getBuffer() == null) {

			// No buffers necessary
			final EventList eventList = transferEnvelope.getEventList();
			if (!eventList.isEmpty()) {
				final Iterator<AbstractEvent> it = eventList.iterator();
				while (it.hasNext()) {
					this.byteBufferedInputChannel.processEvent(it.next());
				}
			}

			return null;
		}

		// TODO: Fix implementation breaks compression, fix it later on
		final BufferPairResponse response = new BufferPairResponse(null, transferEnvelope.getBuffer()); // No need to
																										// copy anything

		// Process events
		final EventList eventList = transferEnvelope.getEventList();
		if (!eventList.isEmpty()) {
			final Iterator<AbstractEvent> it = eventList.iterator();
			while (it.hasNext()) {
				this.byteBufferedInputChannel.processEvent(it.next());
			}
		}

		return response;
	}

	@Override
	public void releaseConsumedReadBuffer() {

		TransferEnvelope transferEnvelope = null;
		synchronized (this.queuedEnvelopes) {

			if (this.queuedEnvelopes.isEmpty()) {
				LOG.error("Inconsistency: releaseConsumedReadBuffer called on empty queue!");
				return;
			}

			transferEnvelope = this.queuedEnvelopes.poll();
		}

		final Buffer consumedBuffer = transferEnvelope.getBuffer();
		if (consumedBuffer == null) {
			LOG.error("Inconsistency: consumed read buffer is null!");
			return;
		}

		if (consumedBuffer.remaining() > 0) {
			LOG.error("consumedReadBuffer has " + consumedBuffer.remaining() + " unconsumed bytes left!!");
		}

		// Recycle consumed read buffer
		consumedBuffer.recycleBuffer();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void transferEventToOutputChannel(AbstractEvent event) throws IOException, InterruptedException {

		final TransferEnvelope ephemeralTransferEnvelope = new TransferEnvelope(0, getJobID(), getChannelID());

		ephemeralTransferEnvelope.addEvent(event);
		this.transferEnvelopeDispatcher.processEnvelopeFromInputChannel(ephemeralTransferEnvelope);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void queueTransferEnvelope(TransferEnvelope transferEnvelope) {

		synchronized (this.queuedEnvelopes) {
			this.queuedEnvelopes.add(transferEnvelope);
		}

		// Notify the channel about the new data
		this.byteBufferedInputChannel.checkForNetworkEvents();
	}

	@Override
	public void reportIOException(IOException ioe) {

		this.byteBufferedInputChannel.reportIOException(ioe);
		this.byteBufferedInputChannel.checkForNetworkEvents();
	}

	@Override
	public ChannelID getChannelID() {

		return this.byteBufferedInputChannel.getID();
	}

	@Override
	public ChannelID getConnectedChannelID() {

		return this.byteBufferedInputChannel.getConnectedChannelID();
	}

	@Override
	public JobID getJobID() {

		return this.byteBufferedInputChannel.getJobID();
	}

	@Override
	public boolean isInputChannel() {

		return this.byteBufferedInputChannel.isInputChannel();
	}

	public void releaseAllResources() {

		final Queue<Buffer> buffersToRecycle = new ArrayDeque<Buffer>();

		synchronized (this.queuedEnvelopes) {

			while (!this.queuedEnvelopes.isEmpty()) {
				final TransferEnvelope envelope = this.queuedEnvelopes.poll();
				if (envelope.getBuffer() != null) {
					buffersToRecycle.add(envelope.getBuffer());
				}
			}
		}

		while (!buffersToRecycle.isEmpty()) {
			buffersToRecycle.poll().recycleBuffer();
		}
	}

	public int getNumberOfQueuedEnvelopes() {

		synchronized (this.queuedEnvelopes) {

			return this.queuedEnvelopes.size();
		}
	}

	public int getNumberOfQueuedMemoryBuffers() {

		synchronized (this.queuedEnvelopes) {

			int count = 0;

			final Iterator<TransferEnvelope> it = this.queuedEnvelopes.iterator();
			while (it.hasNext()) {

				final TransferEnvelope envelope = it.next();
				if (envelope.getBuffer() != null) {
					if (envelope.getBuffer().isBackedByMemory()) {
						++count;
					}
				}
			}

			return count;
		}
	}

	@Override
	public Buffer requestEmptyBuffer(int minimumSizeOfBuffer) throws IOException {

		return this.inputGateContext.requestEmptyBuffer(minimumSizeOfBuffer);
	}

	@Override
	public Buffer requestEmptyBufferBlocking(int minimumSizeOfBuffer) throws IOException, InterruptedException {

		return this.inputGateContext.requestEmptyBufferBlocking(minimumSizeOfBuffer);
	}

	@Override
	public int getMaximumBufferSize() {

		return this.inputGateContext.getMaximumBufferSize();
	}

	@Override
	public boolean isShared() {
		
		return this.inputGateContext.isShared();
	}
}
