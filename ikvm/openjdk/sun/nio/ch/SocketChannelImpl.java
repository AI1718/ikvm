/*
 * Copyright (c) 2000, 2006, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import ikvm.internal.NotYetImplementedError;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.*;


/**
 * An implementation of SocketChannels
 */

class SocketChannelImpl
    extends SocketChannel
    implements SelChImpl
{
    // Our file descriptor object
    private final FileDescriptor fd;
    private volatile cli.System.IAsyncResult asyncConnect;

    // IDs of native threads doing reads and writes, for signalling
    private volatile long readerThread = 0;
    private volatile long writerThread = 0;

    // Lock held by current reading or connecting thread
    private final Object readLock = new Object();

    // Lock held by current writing or connecting thread
    private final Object writeLock = new Object();

    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    private final Object stateLock = new Object();

    // -- The following fields are protected by stateLock

    // State, increases monotonically
    private static final int ST_UNINITIALIZED = -1;
    private static final int ST_UNCONNECTED = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_CONNECTED = 2;
    private static final int ST_KILLPENDING = 3;
    private static final int ST_KILLED = 4;
    private int state = ST_UNINITIALIZED;

    // Binding
    private SocketAddress localAddress = null;
    private SocketAddress remoteAddress = null;

    // Input/Output open
    private boolean isInputOpen = true;
    private boolean isOutputOpen = true;
    private boolean readyToConnect = false;

    // Options, created on demand
    private SocketOpts.IP.TCP options = null;

    // Socket adaptor, created on demand
    private Socket socket = null;

    // -- End of fields protected by stateLock


    // Constructor for normal connecting sockets
    //
    SocketChannelImpl(SelectorProvider sp) throws IOException {
        super(sp);
        this.fd = Net.socket(true);
        this.state = ST_UNCONNECTED;
    }

    // Constructor for sockets obtained from server sockets
    //
    SocketChannelImpl(SelectorProvider sp,
                      FileDescriptor fd, InetSocketAddress remote)
        throws IOException
    {
        super(sp);
        this.fd = fd;
        this.state = ST_CONNECTED;
        this.remoteAddress = remote;
    }

    public Socket socket() {
        synchronized (stateLock) {
            if (socket == null)
                socket = SocketAdaptor.create(this);
            return socket;
        }
    }

    private boolean ensureReadOpen() throws ClosedChannelException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (!isConnected())
                throw new NotYetConnectedException();
            if (!isInputOpen)
                return false;
            else
                return true;
        }
    }

    private void ensureWriteOpen() throws ClosedChannelException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (!isOutputOpen)
                throw new ClosedChannelException();
            if (!isConnected())
                throw new NotYetConnectedException();
        }
    }

    private void readerCleanup() throws IOException {
        synchronized (stateLock) {
            readerThread = 0;
            if (state == ST_KILLPENDING)
                kill();
        }
    }

    private void writerCleanup() throws IOException {
        synchronized (stateLock) {
            writerThread = 0;
            if (state == ST_KILLPENDING)
                kill();
        }
    }

    public int read(ByteBuffer buf) throws IOException {

        if (buf == null)
            throw new NullPointerException();

        synchronized (readLock) {
            if (!ensureReadOpen())
                return -1;
            int n = 0;
            try {

                // Set up the interruption machinery; see
                // AbstractInterruptibleChannel for details
                //
                begin();

                synchronized (stateLock) {
                    if (!isOpen()) {
                    // Either the current thread is already interrupted, so
                    // begin() closed the channel, or another thread closed the
                    // channel since we checked it a few bytecodes ago.  In
                    // either case the value returned here is irrelevant since
                    // the invocation of end() in the finally block will throw
                    // an appropriate exception.
                    //
                        return 0;

                    }

                    // Save this thread so that it can be signalled on those
                    // platforms that require it
                    //
                    readerThread = NativeThread.current();
                }

                // Between the previous test of isOpen() and the return of the
                // IOUtil.read invocation below, this channel might be closed
                // or this thread might be interrupted.  We rely upon the
                // implicit synchronization point in the kernel read() call to
                // make sure that the right thing happens.  In either case the
                // implCloseSelectableChannel method is ultimately invoked in
                // some other thread, so there are three possibilities:
                //
                //   - implCloseSelectableChannel() invokes nd.preClose()
                //     before this thread invokes read(), in which case the
                //     read returns immediately with either EOF or an error,
                //     the latter of which will cause an IOException to be
                //     thrown.
                //
                //   - implCloseSelectableChannel() invokes nd.preClose() after
                //     this thread is blocked in read().  On some operating
                //     systems (e.g., Solaris and Windows) this causes the read
                //     to return immediately with either EOF or an error
                //     indication.
                //
                //   - implCloseSelectableChannel() invokes nd.preClose() after
                //     this thread is blocked in read() but the operating
                //     system (e.g., Linux) doesn't support preemptive close,
                //     so implCloseSelectableChannel() proceeds to signal this
                //     thread, thereby causing the read to return immediately
                //     with IOStatus.INTERRUPTED.
                //
                // In all three cases the invocation of end() in the finally
                // clause will notice that the channel has been closed and
                // throw an appropriate exception (AsynchronousCloseException
                // or ClosedByInterruptException) if necessary.
                //
                // *There is A fourth possibility. implCloseSelectableChannel()
                // invokes nd.preClose(), signals reader/writer thred and quickly
                // moves on to nd.close() in kill(), which does a real close.
                // Then a third thread accepts a new connection, opens file or
                // whatever that causes the released "fd" to be recycled. All
                // above happens just between our last isOpen() check and the
                // next kernel read reached, with the recycled "fd". The solution
                // is to postpone the real kill() if there is a reader or/and
                // writer thread(s) over there "waiting", leave the cleanup/kill
                // to the reader or writer thread. (the preClose() still happens
                // so the connection gets cut off as usual).
                //
                // For socket channels there is the additional wrinkle that
                // asynchronous shutdown works much like asynchronous close,
                // except that the channel is shutdown rather than completely
                // closed.  This is analogous to the first two cases above,
                // except that the shutdown operation plays the role of
                // nd.preClose().
                for (;;) {
                    n = Net.read(fd, buf);
                    if ((n == IOStatus.INTERRUPTED) && isOpen()) {
                        // The system call was interrupted but the channel
                        // is still open, so retry
                        continue;
                    }
                    return IOStatus.normalize(n);
                }

            } finally {
                readerCleanup();        // Clear reader thread
                // The end method, which is defined in our superclass
                // AbstractInterruptibleChannel, resets the interruption
                // machinery.  If its argument is true then it returns
                // normally; otherwise it checks the interrupt and open state
                // of this channel and throws an appropriate exception if
                // necessary.
                //
                // So, if we actually managed to do any I/O in the above try
                // block then we pass true to the end method.  We also pass
                // true if the channel was in non-blocking mode when the I/O
                // operation was initiated but no data could be transferred;
                // this prevents spurious exceptions from being thrown in the
                // rare event that a channel is closed or a thread is
                // interrupted at the exact moment that a non-blocking I/O
                // request is made.
                //
                end(n > 0 || (n == IOStatus.UNAVAILABLE));

                // Extra case for socket channels: Asynchronous shutdown
                //
                synchronized (stateLock) {
                    if ((n <= 0) && (!isInputOpen))
                        return IOStatus.EOF;
                }

                assert IOStatus.check(n);

            }
        }
    }

    private long read0(ByteBuffer[] bufs) throws IOException {
        if (bufs == null)
            throw new NullPointerException();
        synchronized (readLock) {
            if (!ensureReadOpen())
                return -1;
            long n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    readerThread = NativeThread.current();
                }

                for (;;) {
                    n = Net.read(fd, bufs);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    return IOStatus.normalize(n);
                }
            } finally {
                readerCleanup();
                end(n > 0 || (n == IOStatus.UNAVAILABLE));
                synchronized (stateLock) {
                    if ((n <= 0) && (!isInputOpen))
                        return IOStatus.EOF;
                }
                assert IOStatus.check(n);
            }
        }
    }

    public long read(ByteBuffer[] dsts, int offset, int length)
        throws IOException
    {
        if ((offset < 0) || (length < 0) || (offset > dsts.length - length))
            throw new IndexOutOfBoundsException();
        // ## Fix IOUtil.write so that we can avoid this array copy
        return read0(Util.subsequence(dsts, offset, length));
    }

    public int write(ByteBuffer buf) throws IOException {
        if (buf == null)
            throw new NullPointerException();
        synchronized (writeLock) {
            ensureWriteOpen();
            int n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    writerThread = NativeThread.current();
                }
                for (;;) {
                    n = Net.write(fd, buf);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    return IOStatus.normalize(n);
                }
            } finally {
                writerCleanup();
                end(n > 0 || (n == IOStatus.UNAVAILABLE));
                synchronized (stateLock) {
                    if ((n <= 0) && (!isOutputOpen))
                        throw new AsynchronousCloseException();
                }
                assert IOStatus.check(n);
            }
        }
    }

    public long write0(ByteBuffer[] bufs) throws IOException {
        if (bufs == null)
            throw new NullPointerException();
        synchronized (writeLock) {
            ensureWriteOpen();
            long n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    writerThread = NativeThread.current();
                }
                for (;;) {
                    n = Net.write(fd, bufs);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    return IOStatus.normalize(n);
                }
            } finally {
                writerCleanup();
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                synchronized (stateLock) {
                    if ((n <= 0) && (!isOutputOpen))
                        throw new AsynchronousCloseException();
                }
                assert IOStatus.check(n);
            }
        }
    }

    public long write(ByteBuffer[] srcs, int offset, int length)
        throws IOException
    {
        if ((offset < 0) || (length < 0) || (offset > srcs.length - length))
            throw new IndexOutOfBoundsException();
        // ## Fix IOUtil.write so that we can avoid this array copy
        return write0(Util.subsequence(srcs, offset, length));
    }

    protected void implConfigureBlocking(boolean block) throws IOException {
        IOUtil.configureBlocking(fd, block);
    }

    public SocketOpts options() {
        synchronized (stateLock) {
            if (options == null) {
                SocketOptsImpl.Dispatcher d
                    = new SocketOptsImpl.Dispatcher() {
                            int getInt(int opt) throws IOException {
                                return Net.getIntOption(fd, opt);
                            }
                            void setInt(int opt, int arg)
                                throws IOException
                            {
                                Net.setIntOption(fd, opt, arg);
                            }
                        };
                options = new SocketOptsImpl.IP.TCP(d);
            }
            return options;
        }
    }

    // package-private
    int sendOutOfBandData(byte b) throws IOException {
        synchronized (writeLock) {
            ensureWriteOpen();
            int n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    writerThread = NativeThread.current();
                }
                for (;;) {
                    n = sendOutOfBandData(fd, b);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    return IOStatus.normalize(n);
                }
            } finally {
                writerCleanup();
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                synchronized (stateLock) {
                    if ((n <= 0) && (!isOutputOpen))
                        throw new AsynchronousCloseException();
                }
                assert IOStatus.check(n);
            }
        }
    }

    public boolean isBound() {
        synchronized (stateLock) {
            if (state == ST_CONNECTED)
                return true;
            return localAddress != null;
        }
    }

    public SocketAddress localAddress() {
        synchronized (stateLock) {
            if (state == ST_CONNECTED &&
                (localAddress == null ||
                 ((InetSocketAddress)localAddress).getAddress().isAnyLocalAddress())) {
                    // Socket was not bound before connecting or
                    // Socket was bound with an "anyLocalAddress"
                    localAddress = Net.localAddress(fd);
            }
            return localAddress;
        }
    }

    public SocketAddress remoteAddress() {
        synchronized (stateLock) {
            return remoteAddress;
        }
    }

    public SocketChannel bind(SocketAddress local) throws IOException {
        synchronized (readLock) {
            synchronized (writeLock) {
                synchronized (stateLock) {
                    ensureOpenAndUnconnected();
                    if (localAddress != null)
                        throw new AlreadyBoundException();
                    InetSocketAddress isa = Net.checkAddress(local);
                    Net.bind(fd, isa.getAddress(), isa.getPort());
                    localAddress = Net.localAddress(fd);
                }
            }
        }
        return this;
    }

    public boolean isConnected() {
        synchronized (stateLock) {
            return (state == ST_CONNECTED);
        }
    }

    public boolean isConnectionPending() {
        synchronized (stateLock) {
            return (state == ST_PENDING);
        }
    }

    void ensureOpenAndUnconnected() throws IOException { // package-private
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (state == ST_CONNECTED)
                throw new AlreadyConnectedException();
            if (state == ST_PENDING)
                throw new ConnectionPendingException();
        }
    }

    public boolean connect(SocketAddress sa) throws IOException {
        int trafficClass = 0;           // ## Pick up from options
        int localPort = 0;

        synchronized (readLock) {
            synchronized (writeLock) {
                ensureOpenAndUnconnected();
                InetSocketAddress isa = Net.checkAddress(sa);
                SecurityManager sm = System.getSecurityManager();
                if (sm != null)
                    sm.checkConnect(isa.getAddress().getHostAddress(),
                                    isa.getPort());
                synchronized (blockingLock()) {
                    int n = 0;
                    try {
                        try {
                            begin();
                            synchronized (stateLock) {
                                if (!isOpen()) {
                                    return false;
                                }
                                readerThread = NativeThread.current();
                            }
                            for (;;) {
                                InetAddress ia = isa.getAddress();
                                if (ia.isAnyLocalAddress())
                                    ia = InetAddress.getLocalHost();
                                n = connectImpl(ia,
                                                isa.getPort(),
                                                trafficClass);
                                if (  (n == IOStatus.INTERRUPTED)
                                      && isOpen())
                                    continue;
                                break;
                            }
                        } finally {
                            readerCleanup();
                            end((n > 0) || (n == IOStatus.UNAVAILABLE));
                            assert IOStatus.check(n);
                        }
                    } catch (IOException x) {
                        // If an exception was thrown, close the channel after
                        // invoking end() so as to avoid bogus
                        // AsynchronousCloseExceptions
                        close();
                        throw x;
                    }
                    synchronized (stateLock) {
                        remoteAddress = isa;
                        if (n > 0) {

                            // Connection succeeded; disallow further
                            // invocation
                            state = ST_CONNECTED;
                            return true;
                        }
                        // If nonblocking and no exception then connection
                        // pending; disallow another invocation
                        if (!isBlocking())
                            state = ST_PENDING;
                        else
                            assert false;
                    }
                }
                return false;
            }
        }
    }

    public boolean finishConnect() throws IOException {
        synchronized (readLock) {
            synchronized (writeLock) {
                synchronized (stateLock) {
                    if (!isOpen())
                        throw new ClosedChannelException();
                    if (state == ST_CONNECTED)
                        return true;
                    if (state != ST_PENDING)
                        throw new NoConnectionPendingException();
                }
                int n = 0;
                try {
                    try {
                        begin();
                        synchronized (blockingLock()) {
                            synchronized (stateLock) {
                                if (!isOpen()) {
                                    return false;
                                }
                                readerThread = NativeThread.current();
                            }
                            if (!isBlocking()) {
                                for (;;) {
                                    n = checkConnect(fd, false,
                                                     readyToConnect);
                                    if (  (n == IOStatus.INTERRUPTED)
                                          && isOpen())
                                        continue;
                                    break;
                                }
                            } else {
                                for (;;) {
                                    n = checkConnect(fd, true,
                                                     readyToConnect);
                                    if (n == 0) {
                                        // Loop in case of
                                        // spurious notifications
                                        continue;
                                    }
                                    if (  (n == IOStatus.INTERRUPTED)
                                          && isOpen())
                                        continue;
                                    break;
                                }
                            }
                        }
                    } finally {
                        synchronized (stateLock) {
                            readerThread = 0;
                            if (state == ST_KILLPENDING) {
                                kill();
                                // poll()/getsockopt() does not report
                                // error (throws exception, with n = 0)
                                // on Linux platform after dup2 and
                                // signal-wakeup. Force n to 0 so the
                                // end() can throw appropriate exception
                                n = 0;
                            }
                        }
                        end((n > 0) || (n == IOStatus.UNAVAILABLE));
                        assert IOStatus.check(n);
                    }
                } catch (IOException x) {
                    // If an exception was thrown, close the channel after
                    // invoking end() so as to avoid bogus
                    // AsynchronousCloseExceptions
                    close();
                    throw x;
                }
                if (n > 0) {
                    synchronized (stateLock) {
                        state = ST_CONNECTED;
                    }
                    return true;
                }
                return false;
            }
        }
    }

    public final static int SHUT_RD = 0;
    public final static int SHUT_WR = 1;
    public final static int SHUT_RDWR = 2;

    public SocketChannel shutdownInput() throws IOException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            isInputOpen = false;
            shutdown(fd, SHUT_RD);
            if (readerThread != 0)
                NativeThread.signal(readerThread);
            return this;
        }
    }

    public SocketChannel shutdownOutput() throws IOException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            isOutputOpen = false;
            shutdown(fd, SHUT_WR);
            if (writerThread != 0)
                NativeThread.signal(writerThread);
            return this;
        }
    }

    public boolean isInputOpen() {
        synchronized (stateLock) {
            return isInputOpen;
        }
    }

    public boolean isOutputOpen() {
        synchronized (stateLock) {
            return isOutputOpen;
        }
    }

    // AbstractInterruptibleChannel synchronizes invocations of this method
    // using AbstractInterruptibleChannel.closeLock, and also ensures that this
    // method is only ever invoked once.  Before we get to this method, isOpen
    // (which is volatile) will have been set to false.
    //
    protected void implCloseSelectableChannel() throws IOException {
        synchronized (stateLock) {
            isInputOpen = false;
            isOutputOpen = false;

            closeImpl();

            // Signal native threads, if needed.  If a target thread is not
            // currently blocked in an I/O operation then no harm is done since
            // the signal handler doesn't actually do anything.
            //
            if (readerThread != 0)
                NativeThread.signal(readerThread);

            if (writerThread != 0)
                NativeThread.signal(writerThread);

            // If this channel is not registered then it's safe to close the fd
            // immediately since we know at this point that no thread is
            // blocked in an I/O operation upon the channel and, since the
            // channel is marked closed, no thread will start another such
            // operation.  If this channel is registered then we don't close
            // the fd since it might be in use by a selector.  In that case
            // closing this channel caused its keys to be cancelled, so the
            // last selector to deregister a key for this channel will invoke
            // kill() to close the fd.
            //
            if (!isRegistered())
                kill();
        }
    }

    public void kill() throws IOException {
        synchronized (stateLock) {
            if (state == ST_KILLED)
                return;
            if (state == ST_UNINITIALIZED) {
                state = ST_KILLED;
                return;
            }
            assert !isOpen() && !isRegistered();

            // Postpone the kill if there is a waiting reader
            // or writer thread. See the comments in read() for
            // more detailed explanation.
            if (readerThread == 0 && writerThread == 0) {
                closeImpl();
                state = ST_KILLED;
            } else {
                state = ST_KILLPENDING;
            }
        }
    }

    /**
     * Translates native poll revent ops into a ready operation ops
     */
    public boolean translateReadyOps(int ops, int initialOps,
                                     SelectionKeyImpl sk) {
        int intOps = sk.nioInterestOps(); // Do this just once, it synchronizes
        int oldOps = sk.nioReadyOps();
        int newOps = initialOps;

        if ((ops & PollArrayWrapper.POLLNVAL) != 0) {
            // This should only happen if this channel is pre-closed while a
            // selection operation is in progress
            // ## Throw an error if this channel has not been pre-closed
            return false;
        }

        if ((ops & (PollArrayWrapper.POLLERR
                    | PollArrayWrapper.POLLHUP)) != 0) {
            newOps = intOps;
            sk.nioReadyOps(newOps);
            // No need to poll again in checkConnect,
            // the error will be detected there
            readyToConnect = true;
            return (newOps & ~oldOps) != 0;
        }

        if (((ops & PollArrayWrapper.POLLIN) != 0) &&
            ((intOps & SelectionKey.OP_READ) != 0) &&
            (state == ST_CONNECTED))
            newOps |= SelectionKey.OP_READ;

        if (((ops & PollArrayWrapper.POLLCONN) != 0) &&
            ((intOps & SelectionKey.OP_CONNECT) != 0) &&
            ((state == ST_UNCONNECTED) || (state == ST_PENDING))) {
            newOps |= SelectionKey.OP_CONNECT;
            readyToConnect = true;
        }

        if (((ops & PollArrayWrapper.POLLOUT) != 0) &&
            ((intOps & SelectionKey.OP_WRITE) != 0) &&
            (state == ST_CONNECTED))
            newOps |= SelectionKey.OP_WRITE;

        sk.nioReadyOps(newOps);
        return (newOps & ~oldOps) != 0;
    }

    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, sk.nioReadyOps(), sk);
    }

    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, 0, sk);
    }

    /**
     * Translates an interest operation set into a native poll event set
     */
    public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
        int newOps = 0;
        if ((ops & SelectionKey.OP_READ) != 0)
            newOps |= PollArrayWrapper.POLLIN;
        if ((ops & SelectionKey.OP_WRITE) != 0)
            newOps |= PollArrayWrapper.POLLOUT;
        if ((ops & SelectionKey.OP_CONNECT) != 0)
            newOps |= PollArrayWrapper.POLLCONN;
        sk.selector.putEventOps(sk, newOps);
    }

    public FileDescriptor getFD() {
        return fd;
    }

    public int getFDVal() {
        throw new Error();
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(this.getClass().getSuperclass().getName());
        sb.append('[');
        if (!isOpen())
            sb.append("closed");
        else {
            synchronized (stateLock) {
                switch (state) {
                case ST_UNCONNECTED:
                    sb.append("unconnected");
                    break;
                case ST_PENDING:
                    sb.append("connection-pending");
                    break;
                case ST_CONNECTED:
                    sb.append("connected");
                    if (!isInputOpen)
                        sb.append(" ishut");
                    if (!isOutputOpen)
                        sb.append(" oshut");
                    break;
                }
                if (localAddress() != null) {
                    sb.append(" local=");
                    sb.append(localAddress().toString());
                }
                if (remoteAddress() != null) {
                    sb.append(" remote=");
                    sb.append(remoteAddress().toString());
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }


    // -- Native methods --

    private int connectImpl(InetAddress remote, int remotePort, int trafficClass) throws IOException
    {
        try
        {
            if (false) throw new cli.System.Net.Sockets.SocketException();
            if (false) throw new cli.System.ObjectDisposedException("");
            cli.System.Net.IPEndPoint ep = new cli.System.Net.IPEndPoint(SocketUtil.getAddressFromInetAddress(remote), remotePort);
            if (isBlocking())
            {
                fd.getSocket().Connect(ep);
                return 1;
            }
            else
            {
                asyncConnect = fd.getSocket().BeginConnect(ep, null, null);
                return IOStatus.UNAVAILABLE;
            }
        }
        catch (cli.System.Net.Sockets.SocketException x)
        {
            throw new ConnectException(x.getMessage());
        }
        catch (cli.System.ObjectDisposedException x1)
        {
            throw new SocketException("Socket is closed");
        }
    }

    private int checkConnect(FileDescriptor fd, boolean block, boolean ready) throws IOException
    {
        try
        {
            if (false) throw new cli.System.Net.Sockets.SocketException();
            if (false) throw new cli.System.ObjectDisposedException("");
            if (block || ready || asyncConnect.get_IsCompleted())
            {
                cli.System.IAsyncResult res = asyncConnect;
                asyncConnect = null;
                fd.getSocket().EndConnect(res);
                // work around for blocking issue
                fd.getSocket().set_Blocking(isBlocking());
                return 1;
            }
            else
            {
                return IOStatus.UNAVAILABLE;
            }
        }
        catch (cli.System.Net.Sockets.SocketException x)
        {
            throw new ConnectException(x.getMessage());
        }
        catch (cli.System.ObjectDisposedException x1)
        {
            throw new SocketException("Socket is closed");
        }
    }

    private static /*native*/ int sendOutOfBandData(FileDescriptor fd, byte data) throws IOException
    {
    	throw new NotYetImplementedError(); //TODO JDK7
    }
    
    private static void shutdown(FileDescriptor fd, int how) throws IOException
    {
        try
        {
            if (false) throw new cli.System.Net.Sockets.SocketException();
            if (false) throw new cli.System.ObjectDisposedException("");
            fd.getSocket().Shutdown(cli.System.Net.Sockets.SocketShutdown.wrap(how == SHUT_RD ? cli.System.Net.Sockets.SocketShutdown.Receive : cli.System.Net.Sockets.SocketShutdown.Send));
        }
        catch (cli.System.Net.Sockets.SocketException x)
        {
            throw SocketUtil.convertSocketExceptionToIOException(x);
        }
        catch (cli.System.ObjectDisposedException x1)
        {
            throw new SocketException("Socket is closed");
        }
    }

    private void closeImpl() throws IOException
    {
        try
        {
            if (false) throw new cli.System.Net.Sockets.SocketException();
            if (false) throw new cli.System.ObjectDisposedException("");
            fd.getSocket().Close();
        }
        catch (cli.System.Net.Sockets.SocketException x)
        {
            throw SocketUtil.convertSocketExceptionToIOException(x);
        }
        catch (cli.System.ObjectDisposedException x1)
        {
            throw new SocketException("Socket is closed");
        }
    }

}

// temporary compilation stubs
class PollArrayWrapper
{
    static final short POLLIN = 0x0001;
    static final short POLLOUT = 0x0004;
    static final short POLLERR = 0x0008;
    static final short POLLHUP = 0x0010;
    static final short POLLNVAL = 0x0020;
    static final short POLLREMOVE = 0x0800;
    static final short POLLCONN = 0x0002;
}
