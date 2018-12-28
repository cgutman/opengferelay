package opengferelay;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;

import opengferelay.https.HttpsRelay;
import opengferelay.https.PcCryptoProvider;
import opengferelay.simplerelay.TcpRelay;
import opengferelay.simplerelay.UdpRelay;

public class RelayMain {
	private static final String UNIQUE_ID = "f00ff00ff00ff00f";
	private static final PcCryptoProvider cryptoProvider = new PcCryptoProvider();

	private static final int SXS_HTTPS_PORT = 37984;
	private static final int SXS_HTTP_PORT = 37989;
	private static final int DEFAULT_HTTPS_PORT = 47984;
	private static final int DEFAULT_HTTP_PORT = 47989;
	
	// Throws if the port is unavailable
	private static void testPort(int port) throws IOException {
		ServerSocket ss = new ServerSocket(port);
		ss.close();
	}
	
	public static boolean pair(InetAddress host) {
		System.out.println("Checking pair status with server...");
		
		NvHTTP httpConn = new NvHTTP(host, UNIQUE_ID, null, cryptoProvider);
		try {
			if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
				System.out.println("Already paired to server");
				return true;
			}
			else {
				final String pinStr = PairingManager.generatePinString();
				
				System.out.println("Please type the following PIN on the remote PC: "+pinStr);
				
				PairingManager.PairState pairState = httpConn.pair(httpConn.getServerInfo(), pinStr);
				if (pairState == PairingManager.PairState.PIN_WRONG) {
					System.out.println("Incorrect PIN");
				}
				else if (pairState == PairingManager.PairState.FAILED) {
					System.out.println("Pairing failed");
				}
				else if (pairState == PairingManager.PairState.PAIRED) {
					System.out.println("Paired successfully");
					return true;
				}
				
				return false;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	
	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			System.out.println("Usage: opengferelay <IP of remote server> <internal relay IP> <external relay IP>");
			return;
		}
		
		InetAddress remoteAddr = InetAddress.getByName(args[0]);
		
		// Initiate pairing to the remote host
		if (!pair(remoteAddr)) {
			return;
		}

		// HTTPS server
		HttpsRelay httpsRelay = new HttpsRelay(
				InetAddress.getByName(args[1]).getHostAddress(),
				InetAddress.getByName(args[2]).getHostAddress(),
				remoteAddr, UNIQUE_ID, cryptoProvider);
		
		// HTTP server
		HttpsRelay httpRelay = new HttpsRelay(
				InetAddress.getByName(args[1]).getHostAddress(),
				InetAddress.getByName(args[2]).getHostAddress(),
				remoteAddr, UNIQUE_ID, cryptoProvider);
		
		try {
			// Try non-SxS mode first
			testPort(DEFAULT_HTTPS_PORT);
			httpsRelay.start(DEFAULT_HTTPS_PORT, true);
			httpRelay.start(DEFAULT_HTTP_PORT, false);
			
			// If that worked, we'll start the other relays
			
			// Remote input port
			TcpRelay.startRelay(35043, new InetSocketAddress(remoteAddr, 35043));
			
			// Control port
			TcpRelay.startRelay(47995, new InetSocketAddress(remoteAddr, 47995));
			UdpRelay.startRelay(47999, new InetSocketAddress(remoteAddr, 47999));
			
			// RTSP port
			TcpRelay.startRelay(48010, new InetSocketAddress(remoteAddr, 48010));
			UdpRelay.startRelay(48010, new InetSocketAddress(remoteAddr, 48010));
			
			// Video port
			UdpRelay.startRelay(47998, new InetSocketAddress(remoteAddr, 47998));
			
			// Audio port
			UdpRelay.startRelay(48000, new InetSocketAddress(remoteAddr, 48000));

			// Mic port
			UdpRelay.startRelay(48002, new InetSocketAddress(remoteAddr, 48002));
		} catch (Exception e) {
			// Run in SxS mode
			httpsRelay.start(SXS_HTTPS_PORT, true);
			httpRelay.start(SXS_HTTP_PORT, false);
		}
		
		// Wait forever
		for (;;) {
			Thread.sleep(100000);
		}
	}
}
