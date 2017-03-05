package io.mycat.backend.mysql.nio.handler.query.impl;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;

import io.mycat.backend.BackendConnection;
import io.mycat.backend.mysql.nio.handler.query.BaseDMLHandler;
import io.mycat.backend.mysql.nio.handler.util.HandlerTool;
import io.mycat.config.ErrorCode;
import io.mycat.net.mysql.BinaryRowDataPacket;
import io.mycat.net.mysql.EOFPacket;
import io.mycat.net.mysql.ErrorPacket;
import io.mycat.net.mysql.FieldPacket;
import io.mycat.net.mysql.OkPacket;
import io.mycat.net.mysql.ResultSetHeaderPacket;
import io.mycat.net.mysql.RowDataPacket;
import io.mycat.net.mysql.StatusFlags;
import io.mycat.server.NonBlockingSession;
import io.mycat.server.ServerConnection;

/*
 * 最终将数据返回给用户的hander处理
 */
public class OutputHandler extends BaseDMLHandler {
	private static Logger logger = Logger.getLogger(OutputHandler.class);
	/**
	 * 回收资源和其他的response方法有可能同步
	 */
	protected final ReentrantLock lock;

	private byte packetId;
	private NonBlockingSession session;
	private ByteBuffer buffer;
	private boolean isBinary;
	private boolean hasNext;

	public OutputHandler(long id, NonBlockingSession session, boolean hasNext) {
		super(id, session);
		session.setOutputHandler(this);
		this.lock = new ReentrantLock();
		this.packetId = 0;
		this.session = session;
		this.hasNext = hasNext;
		this.isBinary = session.isPrepared();
		this.buffer = session.getSource().allocate();
	}

	@Override
	public HandlerType type() {
		return HandlerType.FINAL;
	}

	@Override
	public void okResponse(byte[] ok, BackendConnection conn) {
		OkPacket okPacket = new OkPacket();
		okPacket.read(ok);
		ServerConnection source = session.getSource();
		lock.lock();
		try {
			ok[3] = ++packetId;
			if ((okPacket.serverStatus & StatusFlags.SERVER_MORE_RESULTS_EXISTS) > 0) {
				buffer = source.writeToBuffer(ok, buffer);
			} else {
				HandlerTool.terminateHandlerTree(this);
				if (hasNext) {
					okPacket.serverStatus |= StatusFlags.SERVER_MORE_RESULTS_EXISTS;
				}
				buffer = source.writeToBuffer(ok, buffer);
				if (hasNext) {
					source.write(buffer);
//					source.excuteNext(packetId, false);
				} else {
//					source.excuteNext(packetId, false);
					source.write(buffer);
				}
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void errorResponse(byte[] err, BackendConnection conn) {
		ErrorPacket errPacket = new ErrorPacket();
		errPacket.read(err);
		logger.warn(new StringBuilder().append(conn.toString()).append("|errorResponse()|").append(errPacket.message)
				.toString());
		lock.lock();
		try {
			buffer = session.getSource().writeToBuffer(err, buffer);
//			session.getSource().excuteNext(packetId, true);
			session.getSource().write(buffer);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void fieldEofResponse(byte[] headernull, List<byte[]> fieldsnull, List<FieldPacket> fieldPackets,
			byte[] eofnull, boolean isLeft, BackendConnection conn) {
		if (terminate.get()) {
			return;
		}
		lock.lock();
		try {
			if (this.isBinary)
				this.fieldPackets = fieldPackets;
			ResultSetHeaderPacket hp = new ResultSetHeaderPacket();
			hp.fieldCount = fieldPackets.size();
			hp.packetId = ++packetId;

			ServerConnection source = session.getSource();
			buffer = hp.write(buffer, source, true);
			for (FieldPacket fp : fieldPackets) {
				fp.packetId = ++packetId;
				buffer = fp.write(buffer, source, true);
			}
			EOFPacket ep = new EOFPacket();
			ep.packetId = ++packetId;
			buffer = ep.write(buffer, source, true);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public boolean rowResponse(byte[] rownull, RowDataPacket rowPacket, boolean isLeft, BackendConnection conn) {
		if (terminate.get()) {
			return true;
		}
		lock.lock();
		try {
			byte[] row;
			if (this.isBinary) {
				BinaryRowDataPacket binRowPacket = new BinaryRowDataPacket();
				binRowPacket.read(this.fieldPackets, rowPacket);
				binRowPacket.packetId = ++packetId;
				buffer = binRowPacket.write(buffer, session.getSource(), true);
			} else {
				if (rowPacket != null) {
					rowPacket.packetId = ++packetId;
					buffer = rowPacket.write(buffer, session.getSource(), true);
				} else {
					row = rownull;
					row[3] = ++packetId;
					buffer = session.getSource().writeToBuffer(row, buffer);
				}
			}
		} finally {
			lock.unlock();
		}
		return false;
	}

	@Override
	public void rowEofResponse(byte[] data, boolean isLeft, BackendConnection conn) {
		if (terminate.get()) {
			return;
		}
		logger.info("--------sql execute end!");
		ServerConnection source = session.getSource();
		lock.lock();
		try {
			EOFPacket eofPacket = new EOFPacket();
			if (data != null) {
				eofPacket.read(data);
			}
			eofPacket.packetId = ++packetId;
			if (hasNext) {
				eofPacket.status |= StatusFlags.SERVER_MORE_RESULTS_EXISTS;
			}
			HandlerTool.terminateHandlerTree(this);
			byte[] eof = eofPacket.toBytes();
			buffer = source.writeToBuffer(eof, buffer);
			if (hasNext) {
				source.write(buffer);
//				source.excuteNext(packetId, false);
			} else {
//				source.excuteNext(packetId, false);
				source.write(buffer);
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void relayPacketResponse(byte[] relayPacket, BackendConnection conn) {
		lock.lock();
		try {
			buffer = session.getSource().writeToBuffer(relayPacket, buffer);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void endPacketResponse(byte[] endPacket, BackendConnection conn) {
		lock.lock();
		try {
			buffer = session.getSource().writeToBuffer(endPacket, buffer);
			session.getSource().write(buffer);
		} finally {
			lock.unlock();
		}
	}

	public void backendConnError(byte[] errMsg) {
		if (terminate.compareAndSet(false, true)) {
			ErrorPacket err = new ErrorPacket();
			err.errno = ErrorCode.ER_YES;
			err.message = errMsg;
			HandlerTool.terminateHandlerTree(this);
			backendConnError(err);
		}
	}

	protected void backendConnError(ErrorPacket error) {
		lock.lock();
		try {
			recycleResources();
			if (error == null) {
				error = new ErrorPacket();
				error.errno = ErrorCode.ER_YES;
				error.message = "unknown error".getBytes();
			}
			error.packetId = ++packetId;
//			session.getSource().excuteNext(packetId, true);
			session.getSource().write(error.toBytes());
		} finally {
			lock.unlock();
		}
	}

	private void recycleResources() {
		if (buffer != null) {
			if (buffer.position() > 0) {
				session.getSource().write(buffer);
			} else {
				session.getSource().recycle(buffer);
				buffer = null;
			}
		}
	}

	@Override
	protected void onTerminate() {
		if (this.isBinary) {
			if (this.fieldPackets != null)
				this.fieldPackets.clear();
		}
	}

}