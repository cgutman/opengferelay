package opengferelay.simplerelay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

public class TcpRelay {
	private ServerSocket serverSock;
	private SocketAddress remoteHost;
	
	private TcpRelay() {}
	
	public static TcpRelay startRelay(int localPort, SocketAddress remoteHost) throws IOException {
		TcpRelay relay = new TcpRelay();
		
		relay.serverSock = new ServerSocket(localPort);
		relay.remoteHost = remoteHost;
		
		relay.startAcceptThread();
		
		return relay;
	}
	
	private void startRelayThread(final Socket sin, final Socket sout) {
		new Thread() {
			@Override
			public void run() {
				byte[] buf = new byte[1500];
				
				InputStream in;
				OutputStream out;
				
				try {
					in = sin.getInputStream();
					out = sout.getOutputStream();
					
					for (;;) {
						int bytesRead = in.read(buf);
						if (bytesRead <= 0) {
							break;
						}

						out.write(buf, 0, bytesRead);
					}
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					// Close up both sides
					try {
						sin.close();
					} catch (IOException e1) {}
					try {
						sout.close();
					} catch (IOException e1) {}
				}
			}
		}.start();
	}
	
	private void startAcceptThread() {
		new Thread() {
			@Override
			public void run() {
				for (;;) {
					Socket clientSock;
					
					System.out.println("Waiting for TCP connections on port: "+serverSock.getLocalPort());
					try {
						clientSock = serverSock.accept();
						clientSock.setTcpNoDelay(true);
					} catch (IOException e) {
						e.printStackTrace();
						break;
					}
					
					System.out.println("Waiting for outbound connection to: "+remoteHost);
					Socket outboundSock = new Socket();
					try {
						outboundSock.connect(remoteHost, 5000);
						outboundSock.setTcpNoDelay(true);
					} catch (IOException e) {
						e.printStackTrace();
						
						// Drop this inbound connection
						try {
							outboundSock.close();
						} catch (IOException e1) {}
						try {
							clientSock.close();
						} catch (IOException e1) {}
						
						// Keep waiting for other inbound connections
						continue;
					}
										
					startRelayThread(clientSock, outboundSock);
					startRelayThread(outboundSock, clientSock);
					
					System.out.println("Relay started for "+clientSock.getRemoteSocketAddress()+" -> "+outboundSock.getRemoteSocketAddress());
				}
			}
		}.start();
	}
}
