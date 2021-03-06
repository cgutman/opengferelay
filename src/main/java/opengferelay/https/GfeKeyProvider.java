package opengferelay.https;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class GfeKeyProvider {

	private File certFile = new File("gfe-server.crt");
	private File keyFile = new File("gfe-server.key");
	
	private ArrayList<X509Certificate> certs;
	private RSAPrivateKey key;
	
	static {
		// Install the Bouncy Castle provider
		Security.addProvider(new BouncyCastleProvider());
	}
	
	private byte[] loadFileToBytes(File f) {
		if (!f.exists()) {
			return null;
		}
		
		try {
			FileInputStream fin = new FileInputStream(f);
			byte[] fileData = new byte[(int) f.length()];
			fin.read(fileData);
			fin.close();
			return fileData;
		} catch (IOException e) {
			return null;
		}
	}
	
	private boolean loadCertKeyPair() {
		byte[] certBytes = loadFileToBytes(certFile);
		byte[] keyBytes = loadFileToBytes(keyFile);
		
		// If either file was missing, we definitely can't succeed
		if (certBytes == null || keyBytes == null) {
			System.out.println("Missing cert or key; need to generate a new one");
			return false;
		}
		
		try {
			CertificateFactory certFactory = CertificateFactory.getInstance("X.509", "BC");
			
			ByteArrayInputStream bis = new ByteArrayInputStream(certBytes);
			certs = new ArrayList<>();
			while (bis.available() > 0) {
				certs.add((X509Certificate) certFactory.generateCertificate(bis));
			}
			System.out.println("Certificate chain length: "+certs.size());
			
			KeyFactory keyFactory = KeyFactory.getInstance("RSA", "BC");
			key = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
		} catch (Exception e) {
			// The user may have manually placed these files, so don't wipe them out
			// if we can't read them. Just throw an exception and let them figure it out.
			throw new RuntimeException(e);
		}
		
		System.out.println("Loaded key pair from disk");
		return true;
	}
	
	private boolean generateCertKeyPair() {
		byte[] snBytes = new byte[8];
		new SecureRandom().nextBytes(snBytes);
		
		KeyPair keyPair;
		try {
			KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
			keyPairGenerator.initialize(2048);
			keyPair = keyPairGenerator.generateKeyPair();
		} catch (NoSuchAlgorithmException e1) {
			// Should never happen
			e1.printStackTrace();
			return false;
		} catch (NoSuchProviderException e) {
			// Should never happen
			e.printStackTrace();
			return false;
		}
		
		Date now = new Date();
		
		// Expires in 20 years
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(now);
		calendar.add(Calendar.YEAR, 20);
		Date expirationDate = calendar.getTime();
		
		BigInteger serial = new BigInteger(snBytes).abs();
		
		X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
		nameBuilder.addRDN(BCStyle.CN, "NVIDIA GameStream Server");
		X500Name name = nameBuilder.build();
		
		X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(name, serial, now, expirationDate, name, keyPair.getPublic());
				
		try {
			ContentSigner sigGen = new JcaContentSignerBuilder("SHA256withRSA").setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keyPair.getPrivate());
			certs = new ArrayList<>();
			certs.add(new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(certBuilder.build(sigGen)));
			key = (RSAPrivateKey) keyPair.getPrivate();
		} catch (Exception e) {
			// Nothing should go wrong here
			e.printStackTrace();
			return false;
		}
		
		System.out.println("Generated a new key pair");
		
		// Save the resulting pair
		saveCertKeyPair();
		
		return true;
	}
	
	private void saveCertKeyPair() {
		try {
			FileOutputStream certOut = new FileOutputStream(certFile);
			FileOutputStream keyOut = new FileOutputStream(keyFile);
			
			// Write the certificate in OpenSSL PEM format (important for the server)
			StringWriter strWriter = new StringWriter();
			JcaPEMWriter pemWriter = new JcaPEMWriter(strWriter);
			for (X509Certificate cert : certs) {
				pemWriter.writeObject(cert);
			}
			pemWriter.close();
			
			// Line endings MUST be UNIX for the PC to accept the cert properly
			OutputStreamWriter certWriter = new OutputStreamWriter(certOut);
			String pemStr = strWriter.getBuffer().toString();
			for (int i = 0; i < pemStr.length(); i++) {
				char c = pemStr.charAt(i);
				if (c != '\r')
					certWriter.append(c);
			}
			certWriter.close();
			
			// Write the private out in PKCS8 format
			keyOut.write(key.getEncoded());
			
			certOut.close();
			keyOut.close();
			
			System.out.println("Saved generated key pair to disk");
		} catch (IOException e) {
			// This isn't good because it means we'll have
			// to re-pair next time
			e.printStackTrace();
		}
	}
	
	public X509Certificate[] getServerCertificateChain() {
		// Use a lock here to ensure only one guy will be generating or loading
		// the certificate and key at a time
		synchronized (this) {
			if (certs == null) {
				// No loaded cert yet, let's see if we have one on disk
				if (!loadCertKeyPair()) {
					// Nope, try to generate a new key pair
					if (!generateCertKeyPair()) {
						// Failed
						return null;
					}
					
					// Load the generated pair
					loadCertKeyPair();
				}
			}
			
			return certs.toArray(new X509Certificate[0]);
		}
	}

	public RSAPrivateKey getServerPrivateKey() {
		// Use a lock here to ensure only one guy will be generating or loading
		// the certificate and key at a time
		synchronized (this) {
			// Return a loaded key if we have one
			if (key != null) {
				return key;
			}
			
			// No loaded key yet, let's see if we have one on disk
			if (loadCertKeyPair()) {
				// Got one
				return key;
			}
			
			// Try to generate a new key pair
			if (!generateCertKeyPair()) {
				// Failed
				return null;
			}
			
			// Load the generated pair
			loadCertKeyPair();
			return key;
		}
	}
}
