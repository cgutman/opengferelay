package opengferelay.simplerelay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;

public class UdpRelay {
	private DatagramSocket dgramSocket;
	private SocketAddress remoteAddress;
	
	private UdpRelay() {}
	
	public static UdpRelay startRelay(int localPort, SocketAddress remoteAddress) throws IOException {
		UdpRelay relay = new UdpRelay();
		
		relay.dgramSocket = new DatagramSocket(localPort);
		relay.remoteAddress = remoteAddress;
		
		relay.startRelayThread();
		
		return relay;
	}
	
	private void startRelayThread() {
		new Thread() {
			@Override
			public void run() {
				DatagramPacket pkt = new DatagramPacket(new byte[1500], 0);
				SocketAddress clientAddr = null;
				
				System.out.println("Waiting for UDP datagrams on port: "+dgramSocket.getLocalPort());
				
				try {
					for (;;) {
						// Reset the length back to the original value
						pkt.setLength(1500);
						
						// Wait for a UDP datagram
						dgramSocket.receive(pkt);
						
						// This is from the remote host and needs to be relayed to our last known client
						if (pkt.getSocketAddress().equals(remoteAddress)) {
							if (clientAddr != null) {
								pkt.setSocketAddress(clientAddr);
								dgramSocket.send(pkt);
							}
							else {
								System.err.println("Warning: Dropping remote packet with no soliciting client");
							}
						}
						else {
							// Remember this client address
							clientAddr = pkt.getSocketAddress();
							
							pkt.setSocketAddress(remoteAddress);
							dgramSocket.send(pkt);
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					dgramSocket.close();
				}
			}
		}.start();
	}
}
