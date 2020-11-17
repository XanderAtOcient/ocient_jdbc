package com.ocient.jdbc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLContext;
import sun.security.util.HostnameChecker;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.interfaces.DHPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import java.util.Base64;
import java.util.HashMap;

import com.google.protobuf.ByteString;
import com.ocient.jdbc.proto.ClientWireProtocol;
import com.ocient.jdbc.proto.ClientWireProtocol.ClientConnection;
import com.ocient.jdbc.proto.ClientWireProtocol.CloseConnection;
import com.ocient.jdbc.proto.ClientWireProtocol.ConfirmationResponse;
import com.ocient.jdbc.proto.ClientWireProtocol.ConfirmationResponse.ResponseType;
import com.ocient.jdbc.proto.ClientWireProtocol.GetSchema;
import com.ocient.jdbc.proto.ClientWireProtocol.Request;
import com.ocient.jdbc.proto.ClientWireProtocol.SetSchema;
import com.ocient.jdbc.proto.ClientWireProtocol.ForceExternal;
import com.ocient.jdbc.proto.ClientWireProtocol.SetPSO;
import com.ocient.jdbc.proto.ClientWireProtocol.TestConnection;

public class XGConnection implements Connection {
	private static final Logger LOGGER = Logger.getLogger("com.ocient.jdbc");

	  public enum Tls {
		  OFF,    // No TLS
		  UNVERIFIED, // Don't verify certificates
		  ON,     // TLS but no server identity verification
		  VERIFY, // TLS with server identity verification
	  }

	private class TestConnectionThread extends Thread {
		Exception e = null;

		@Override
		public void run() {
			try {
				LOGGER.log(Level.INFO, "Testing connection");

				// send request
				final ClientWireProtocol.TestConnection.Builder builder = ClientWireProtocol.TestConnection
						.newBuilder();
				final TestConnection msg = builder.build();
				final ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
				b2.setType(ClientWireProtocol.Request.RequestType.TEST_CONNECTION);
				b2.setTestConnection(msg);
				final Request wrapper = b2.build();

				try {
					out.write(intToBytes(wrapper.getSerializedSize()));
					wrapper.writeTo(out);
					out.flush();
					getStandardResponse();
				} catch (SQLException | IOException e) {
					LOGGER.log(Level.WARNING, String.format("Connection test failed with exception %s with message %s",
							e.toString(), e.getMessage()));
					if (e instanceof SQLException && !SQLStates.UNEXPECTED_EOF.equals((SQLException) e)) {
						throw e;
					}

					reconnect();
					run();
					return;
				}
			} catch (final Exception e) {
				this.e = e;
			}
		}
	}

	private static int bytesToInt(final byte[] val) {
		final int ret = java.nio.ByteBuffer.wrap(val).getInt();
		return ret;
	}

	private static byte[] intToBytes(final int val) {
		final byte[] buff = new byte[4];
		buff[0] = (byte) (val >> 24);
		buff[1] = (byte) ((val & 0x00FF0000) >> 16);
		buff[2] = (byte) ((val & 0x0000FF00) >> 8);
		buff[3] = (byte) ((val & 0x000000FF));
		return buff;
	}
	
	private class XGTrustManager extends X509ExtendedTrustManager {
		X509TrustManager defaultTm;
		Tls tls;

		public XGTrustManager(Tls t) throws Exception {
			super();
			tls = t;
			
			TrustManagerFactory tmf = TrustManagerFactory
				.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			
			tmf.init((java.security.KeyStore) null);
			
			for (TrustManager tm : tmf.getTrustManagers()) {
				if (tm instanceof X509TrustManager) {
					defaultTm = (X509TrustManager) tm;
					break;
				}
			}
		}
				
		@Override
		public void checkServerTrusted(X509Certificate certificates[],
									   String s,
									   javax.net.ssl.SSLEngine sslEngine) throws CertificateException {
			LOGGER.log(Level.INFO,"x509ExtendedTrustManager: checkServerTrusted "+s+"with sslEngine");
			checkServerTrusted(certificates, s);
		}
		
		@Override
		public void checkServerTrusted(X509Certificate[] certificates,
									   String s,
									   java.net.Socket socket) throws CertificateException {
			LOGGER.log(Level.INFO,"x509ExtendedTrustManager: checkServerTrusted "+s+"with socket");
			checkServerTrusted(certificates, s);

			// Do host name verification
			if (tls == Tls.VERIFY) {
				throw new UnsupportedOperationException("TLS Verify mode not supported");
			}
		}
		
		@Override
		public void checkServerTrusted(X509Certificate[] certificates,
									   String s) throws CertificateException {
			LOGGER.log(Level.INFO,"x509ExtendedTrustManager: checkServerTrusted "+s);
			try {
				defaultTm.checkServerTrusted(certificates, s);
			} catch (CertificateException e) {
				
				// Rethrow the exception if we are not using level ON
				if (tls != tls.UNVERIFIED)
					throw e;
				else
					LOGGER.log(Level.WARNING, "Ignoring certificate exception: "+e.getMessage());
			}
		}
		
		@Override
		public void checkClientTrusted(X509Certificate certificates[],
									   String s,
									   javax.net.ssl.SSLEngine sslEngine) throws CertificateException {
			LOGGER.log(Level.INFO,"x509ExtendedTrustManager: checkClientTrusted "+s+"with sslEngine");
			checkClientTrusted(certificates, s);
		}
		
		@Override
		public void checkClientTrusted(X509Certificate[] certificates,
									   String s,
									   java.net.Socket socket) throws CertificateException{
			LOGGER.log(Level.INFO,"x509ExtendedTrustManager: checkClientTrusted "+s+"with socket");
			checkClientTrusted(certificates, s);
		}
		
		@Override
		public void checkClientTrusted(X509Certificate[] certificates,
									   String s) throws CertificateException {
			
			LOGGER.log(Level.INFO,"x509ExtendedTrustManager: checkClientTrusted "+s);
			try {
				defaultTm.checkClientTrusted(certificates, s);
			} catch (CertificateException e) {
				LOGGER.log(Level.WARNING, "checkClientTrusted caught "+e.getMessage());

				// Rethrow the exception if we are not using level ON
				if (tls != Tls.UNVERIFIED)
					throw e;
				else
					LOGGER.log(Level.WARNING, "Ignoring certificate exception: "+e.getMessage());
			}
		}
		
		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return defaultTm.getAcceptedIssuers();
		}
		
	};

	protected BufferedInputStream in;
	protected BufferedOutputStream out;
	private boolean closed = false;
	private boolean connected = true;
	private Socket sock;
	protected XGResultSet rs;
	protected int portNum;
	protected ArrayList<SQLWarning> warnings = new ArrayList<>();
	protected final String url;
	protected String ip;
	protected String originalIp;
	protected String connectedIp;
	protected int originalPort;
	protected int connectedPort;
	protected String user;
	protected String database;
	protected String client = "jdbc";
	protected String driverVersion;
	protected String serverVersion = "";
	protected String setSchema = "";
	protected long setPso = 0;
	protected boolean force = false;
	private volatile long timeoutMillis = 0L; // 0L means no timeout set

	protected boolean oneShotForce = false;
	protected ArrayList<String> cmdcomps = new ArrayList<>();
	protected ArrayList<ArrayList<String>> secondaryInterfaces = new ArrayList<ArrayList<String>>();
	protected int secondaryIndex = -1;
	protected int networkTimeout = 10000;
	protected Tls tls;

	// The timer is initially null, created when the first query timeout is set and
	// destroyed on close()
	private final AtomicReference<Timer> timer = new AtomicReference<>();

	protected String pwd;
	private int retryCounter;

	protected Map<String, Class<?>> typeMap;

	public XGConnection(final String user, final String pwd, final String ip, final int portNum, final String url,
						final String database, final String driverVersion, final String force, final Tls tls) throws Exception {
		originalIp = ip;
		originalPort = portNum;
		
		LOGGER.log(Level.INFO, String.format("Connection constructor is setting IP = %s and PORT = %d", ip, portNum));
		
		if (force.equals("true")) {
			this.force = true;
		}

		this.url = url;
		this.user = user;
		this.pwd = pwd;
		this.ip = ip;
		this.portNum = portNum;
		this.database = database;
		this.driverVersion = driverVersion;
		this.retryCounter = 0;
		this.tls = tls;
		this.typeMap = new HashMap<String, Class<?>>();
	}

	public XGConnection(final String user, final String pwd, final int portNum, final String url, final String database,
						final String driverVersion, final boolean force, final Tls tls) {
		this.force = force;
		this.url = url;
		this.user = user;
		this.pwd = pwd;
		this.sock = null;
		this.portNum = portNum;
		this.database = database;
		this.driverVersion = driverVersion;
		this.retryCounter = 0;
		this.tls = tls;
		this.typeMap = new HashMap<String, Class<?>>();
		in = null;
		out = null;
	}

	public XGConnection copy() throws SQLException{
		return copy(false, false);
	}
	
	public XGConnection copy(final boolean shouldRequestVersion) throws SQLException {
		return copy(shouldRequestVersion, false);
	}

	@SuppressWarnings("unchecked")
	public XGConnection copy(final boolean shouldRequestVersion, boolean noRedirect) throws SQLException{
		boolean doForce = force;
		if (noRedirect)
		{
			doForce = true;
		}
		
		XGConnection retval = new XGConnection(user, pwd, portNum, url, database, driverVersion, doForce, tls);
		try {
			retval.connected = false;
			retval.setSchema = setSchema;
			retval.setPso = setPso;
			retval.timeoutMillis = timeoutMillis;
			retval.cmdcomps = (ArrayList<String>) cmdcomps.clone();
			retval.secondaryInterfaces = (ArrayList<ArrayList<String>>)secondaryInterfaces.clone();
			retval.secondaryIndex = secondaryIndex;
			retval.ip = ip;
			retval.originalIp = originalIp;
			retval.connectedIp = connectedIp;
			retval.connectedPort = connectedPort;
			retval.originalPort = originalPort;
			retval.tls = tls;
			retval.serverVersion = serverVersion;
			retval.reconnect(shouldRequestVersion);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE,
					String.format("Copying the connection for a new statement failed with exception %s with message %s",
							e.toString(), e.getMessage()));
			try {
				retval.close();
			} catch (Exception f) {
			}
			throw new SQLException(e);
		}

		return retval;
	}

	public void setServerVersion(String version)
	{
		//Versions are major.minor.patch-date
		//don't want the date
		String cleanVersion = version.indexOf("-") == -1 ? version : version.substring(0, version.indexOf("-"));
		this.serverVersion = cleanVersion;
	}

	public String getServerVersion()
	{
		return serverVersion;
	}

	@Override
	public void abort(final Executor executor) throws SQLException {
		LOGGER.log(Level.INFO, "Called abort()");
		if (executor == null) {
			LOGGER.log(Level.WARNING, "abort() is throwing INVALID_ARGUMENT");
			throw SQLStates.INVALID_ARGUMENT.clone();
		}

		if (closed) {
			return;
		}

		this.close();
	}

	public void clearOneShotForce() {
		oneShotForce = false;
	}

	@Override
	public void clearWarnings() throws SQLException {
		LOGGER.log(Level.INFO, "Called clearWarnings()");
		if (closed) {
			LOGGER.log(Level.WARNING, "clearWarnings() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		warnings.clear();
	}

	/*
	 * ! This timeout will be applied to every XGStatement created
	 */
	public void setTimeout(final int seconds) throws SQLException {
		if (seconds < 0) {
			LOGGER.log(Level.WARNING, "Throwing because a negative value was passed to setTimeout()");
			throw new SQLWarning(String.format("timeout value must be non-negative, was: %s", seconds));
		}

		LOGGER.log(Level.INFO, String.format("Setting timeout to %d seconds", seconds));
		this.timeoutMillis = seconds * 1000;
	}

	protected long getTimeoutMillis() {
		return timeoutMillis;
	}

	/**
	 * Creates a new {@link Timer} or returns the existing one if it already exists
	 */
	private Timer getTimer() {
		return this.timer.updateAndGet(existing -> existing != null ? existing : new Timer());
	}

	/**
	 * Schedules the task to run after the specified delay
	 *
	 * @param task    the task to run
	 * @param timeout delay in milliseconds
	 */
	protected void addTimeout(final TimerTask task, final long timeout) {
		getTimer().schedule(task, timeout);
	}

	/**
	 * Purges all canceled tasks from the timer.
	 *
	 * Note: You should only call this if you've canceled a timer. This call may
	 * create a {@link Timer} object if one does not already exist
	 */
	protected void purgeTimeoutTasks() {
		getTimer().purge();
	}

  public void connect() throws Exception {
	  connect(ip, portNum);
	  try {
		  clientHandshake(user, pwd, database, true);
	  } catch (final Exception e) {
		  e.printStackTrace();
		  throw e;
	  }
  }

  private void connect(final String ip, int port) throws Exception {
	LOGGER.log(Level.INFO,String.format("Trying to connect to IP: %s at port: %d", ip, port));
    try {
      switch (this.tls) {
      case OFF:
		LOGGER.log(Level.INFO,"Unencrypted connection");
        sock = new Socket();
        sock.setReceiveBufferSize(4194304);
        sock.setSendBufferSize(4194304);
        sock.connect(new InetSocketAddress(ip, port), networkTimeout);
        in = new BufferedInputStream(sock.getInputStream());
		out = new BufferedOutputStream(sock.getOutputStream());
		connectedIp = ip;
		connectedPort = port;
        break;

      case UNVERIFIED:
	  case ON:
	  case VERIFY:
		  
		LOGGER.log(Level.INFO,"TLS Connection "+tls.name());
		SSLContext sc = SSLContext.getInstance("TLS");
		  
		TrustManager[] tms = new TrustManager[] { new XGTrustManager(this.tls) };
		
		sc.init(null, tms, null);
        SSLSocketFactory sslsocketfactory = sc.getSocketFactory();
		SSLSocket sslsock = (SSLSocket)sslsocketfactory.createSocket(ip, port);
        sslsock.setReceiveBufferSize(4194304);
        sslsock.setSendBufferSize(4194304);
		sslsock.setUseClientMode(true);
        sslsock.startHandshake();
		sock = sslsock;
        in = new BufferedInputStream(sock.getInputStream());
		out = new BufferedOutputStream(sock.getOutputStream());
		connectedIp = ip;
		connectedPort = port;
		break;
      }
    } catch (Exception e) {
      try {
        if (in != null)
          in.close();
          in = null;
      } catch (final IOException f) {
      }
      try {
        if (out != null)
          out.close();
          out = null;
      } catch (final IOException f) {
      }
      try {
        if (sock != null)
          sock.close();
          sock = null;
      } catch (final IOException f) {
      }
      throw e;
    }
  }
	/*!
	 * Utility for checking if the previously connected ip and port is still available.
	 */ 
  	boolean isSockConnected(){
		try{
			Socket testSocket = new Socket();
			testSocket.connect(new InetSocketAddress(connectedIp, connectedPort), networkTimeout);
			testSocket.close();
			return true;
		} catch (Exception e){
			LOGGER.log(Level.WARNING,"isSockConnected() discovered connection is not working.");
			return false;
		}
	}

  private void clientHandshake(final String userid, final String pwd, final String db, final boolean shouldRequestVersion) throws Exception {
		try {
			LOGGER.log(Level.INFO, "Beginning handshake");
			// send first part of handshake - contains userid
			final ClientWireProtocol.ClientConnection.Builder builder = ClientWireProtocol.ClientConnection
					.newBuilder();
			builder.setUserid(userid);
			builder.setDatabase(database);
			builder.setClientid(client);
			builder.setVersion(driverVersion);
			final ClientConnection msg = builder.build();
			ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
			b2.setType(ClientWireProtocol.Request.RequestType.CLIENT_CONNECTION);
			b2.setClientConnection(msg);
			Request wrapper = b2.build();
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();

			// get response
			final ClientWireProtocol.ClientConnectionResponse.Builder ccr = ClientWireProtocol.ClientConnectionResponse
					.newBuilder();
			int length = getLength();
			byte[] data = new byte[length];
			readBytes(data);
			ccr.mergeFrom(data);
			ConfirmationResponse response = ccr.getResponse();
			ResponseType rType = response.getType();
			processResponseType(rType, response);
			final ByteString ivString = ccr.getIv();
			byte[] key = new byte[256];
			byte[] macKey = new byte[256];
			String myPubKey;

			try {
				String keySpec = ccr.getPubKey();
				keySpec = keySpec.replace("-----BEGIN PUBLIC KEY-----\n", "");
				keySpec = keySpec.replace("-----END PUBLIC KEY-----\n", "");
				byte[] keyBytes = Base64.getMimeDecoder().decode(keySpec.getBytes(StandardCharsets.UTF_8));
				X509EncodedKeySpec x509keySpec = new X509EncodedKeySpec(keyBytes);
				KeyFactory keyFact = KeyFactory.getInstance("DH");
				DHPublicKey pubKey = (DHPublicKey) keyFact.generatePublic(x509keySpec);
				DHParameterSpec params = pubKey.getParams();

				KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
				keyGen.initialize(params);
				KeyPair kp = keyGen.generateKeyPair();

				KeyAgreement ka = KeyAgreement.getInstance("DiffieHellman");
				ka.init(kp.getPrivate());
				ka.doPhase(pubKey, true);
				byte[] secret = ka.generateSecret();

				byte[] buffer = new byte[5 + secret.length];
				buffer[0] = (byte) ((secret.length & 0xff000000) >> 24);
				buffer[1] = (byte) ((secret.length & 0xff0000) >> 16);
				buffer[2] = (byte) ((secret.length & 0xff00) >> 8);
				buffer[3] = (byte) (secret.length & 0xff);
				System.arraycopy(secret, 0, buffer, 5, secret.length);

				buffer[4] = 0x00;
				MessageDigest sha = MessageDigest.getInstance("SHA-256");
				key = sha.digest(buffer);

				buffer[4] = 0x01;
				sha = MessageDigest.getInstance("SHA-256");
				macKey = sha.digest(buffer);

				PublicKey clientPub = kp.getPublic();
				myPubKey = "-----BEGIN PUBLIC KEY-----\n"
						+ Base64.getMimeEncoder().encodeToString(clientPub.getEncoded())
						+ "\n-----END PUBLIC KEY-----\n";
			} catch (Exception e) {
				LOGGER.log(Level.WARNING,
						String.format("Exception %s occurred during handshake with message %s", e.toString(), e.getMessage()));
				throw e;
			}

			final byte[] iv = ivString.toByteArray();
			final IvParameterSpec ips = new IvParameterSpec(iv);

			// Create a key specification first, based on our key input.
			final SecretKey aesKey = new SecretKeySpec(key, "AES");
			final SecretKey hmacKey = new SecretKeySpec(macKey, "AES");

			// Create a Cipher for encrypting the data using the key we created.
			Cipher encryptCipher;

			encryptCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			// Initialize the Cipher with key and parameters
			encryptCipher.init(Cipher.ENCRYPT_MODE, aesKey, ips);

			// Our cleartext
			final byte[] cleartext = pwd.getBytes(StandardCharsets.UTF_8);

			// Encrypt the cleartext
			final byte[] ciphertext = encryptCipher.doFinal(cleartext);

			Mac hmac = Mac.getInstance("HmacSha256");
			hmac.init(hmacKey);
			final byte[] calculatedMac = hmac.doFinal(ciphertext);

			// send handshake part2
			LOGGER.log(Level.INFO, "Beginning handshake part 2");
			final ClientWireProtocol.ClientConnection2.Builder hand2 = ClientWireProtocol.ClientConnection2
					.newBuilder();
			hand2.setCipher(ByteString.copyFrom(ciphertext));
			hand2.setPubKey(myPubKey);
			hand2.setHmac(ByteString.copyFrom(calculatedMac));
			if (force) {
				hand2.setForce(true);
			} else if (oneShotForce) {
				oneShotForce = false;
				hand2.setForce(true);
			} else {
				hand2.setForce(false);
			}
			final ClientWireProtocol.ClientConnection2 msg2 = hand2.build();
			b2 = ClientWireProtocol.Request.newBuilder();
			b2.setType(ClientWireProtocol.Request.RequestType.CLIENT_CONNECTION2);
			b2.setClientConnection2(msg2);
			wrapper = b2.build();
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();

			// getResponse
			final ClientWireProtocol.ClientConnection2Response.Builder ccr2 = ClientWireProtocol.ClientConnection2Response
					.newBuilder();
			length = getLength();
			data = new byte[length];
			readBytes(data);
			ccr2.mergeFrom(data);
			response = ccr2.getResponse();
			rType = response.getType();

			LOGGER.log(Level.INFO, "Handshake response received");
			SQLException state = new SQLException(response.getReason(), response.getSqlState(),
					response.getVendorCode());
			// if we had a failed handshake, then something went wrong with verification on
			// the server, just try again(up to 5 times)
			if (SQLStates.FAILED_HANDSHAKE.equals(state) && retryCounter++ < 5) {
				LOGGER.log(Level.INFO, "Handshake failed, retrying");
				clientHandshake(userid, pwd, db, shouldRequestVersion);
				return;
			}
			retryCounter = 0;
			processResponseType(rType, response);
			
			final int count = ccr2.getCmdcompsCount();
			cmdcomps.clear();
			for (int i = 0; i < count; i++) {
				cmdcomps.add(ccr2.getCmdcomps(i));
			}
			LOGGER.log(Level.INFO,"Clearing and adding new secondary interfaces");
			secondaryInterfaces.clear();
			for (int i = 0; i < ccr2.getSecondaryCount(); i++)
			{
				secondaryInterfaces.add(new ArrayList<String>());
				for (int j = 0; j < ccr2.getSecondary(i).getAddressCount(); j++)
				{
					//Do hostname / IP translation here
					String connInfo = ccr2.getSecondary(i).getAddress(j);
					final StringTokenizer tokens = new StringTokenizer(connInfo, ":", false);
					final String connHost = tokens.nextToken();
					final int connPort = Integer.parseInt(tokens.nextToken());
					final InetAddress[] addrs = InetAddress.getAllByName(connHost);
					for (InetAddress addr : addrs)
					{
						connInfo = addr.toString().substring(addr.toString().indexOf('/') + 1)
								+ ":" + connPort;
						secondaryInterfaces.get(i).add(connInfo);
					}
				}
			}
			
			//Figure out what secondary index it is
			String combined = ip + ":" + this.portNum;
			for (ArrayList<String> list : secondaryInterfaces)
			{
				int index = 0;
				for (String address : list)
				{
					if (address.equals(combined))
					{
						secondaryIndex = index;
						break;
					}
					
					index++;
				}
			}
			
			LOGGER.log(Level.INFO, String.format("Using secondary index %d", secondaryIndex));
			for (ArrayList<String> list : secondaryInterfaces)
			{
				LOGGER.log(Level.INFO, "New SQL node");
				for (String address : list)
				{
					LOGGER.log(Level.INFO, String.format("Interface %s", address));
				}
			}
			
			if (ccr2.getRedirect()) {
				LOGGER.log(Level.INFO, "Redirect command in ClientConnection2Response from server");
				final String host = ccr2.getRedirectHost();
				final int port = ccr2.getRedirectPort();
				redirect(host, port, shouldRequestVersion);
				// We have a lot of dangerous circular function calls.
				// If we were redirected, then we already have the server version. We need to return here.
				if(getVersion() != ""){
					LOGGER.log(Level.WARNING, String.format("Returning in redirect because we were redirected with address: %s",serverVersion));
					return;
				}
			} 
		} catch (final Exception e) {
			LOGGER.log(Level.WARNING,
					String.format("Exception %s occurred during handshake with message %s", e.toString(), e.getMessage()));
			e.printStackTrace();

			try {
				sock.close();
			} catch (final Exception f) {
			}

			throw e;
		}

		if(shouldRequestVersion)
		{
			fetchServerVersion();
		}
		LOGGER.log(Level.INFO,"Handshake Fiinished");
	}

	private void fetchServerVersion() throws Exception
	{
		LOGGER.log(Level.INFO,"Attempting to fetch server version");
		try{
			XGStatement stmt = new XGStatement(this, false);
			String version = stmt.fetchSystemMetadataString(
				ClientWireProtocol.FetchSystemMetadata.SystemMetadataCall.GET_DATABASE_PRODUCT_VERSION);
			LOGGER.log(Level.INFO,String.format("Fetched server version: %s", version));
			setServerVersion(version);
		}
		catch (final Exception e)
		{
			LOGGER.log(Level.WARNING,
					String.format("Exception %s occurred while fetching server version with message %s", e.toString(), e.getMessage()));
			try {
				sock.close();
			} catch (final Exception f) {
			}

			throw e;
		}
	} 

	@Override
	public void close() throws SQLException {
		LOGGER.log(Level.INFO, "close() called on the connection");
		if (closed) {
			return;
		}

		if (rs != null && !rs.isClosed()) {
			rs.getStatement().cancel();
		}

		closed = true;

		if (sock != null) {
			try {
				sendClose();
			} catch (final Exception e) {
			}
		}

		try {
			if (in != null) {
				in.close();
			}

			if (out != null) {
				out.close();
			}

			if (sock != null) {
				sock.close();
			}
		} catch (final Exception e) {
		}

		// Cleanup our timer, if one exists
		Timer t = null;
		do {
			t = timer.get();
			if (t == null) {
				return;
			}
		} while (!timer.compareAndSet(t, null));
		t.cancel();
	}

	@Override
	public void commit() throws SQLException {
		LOGGER.log(Level.INFO, "Called commit()");
		if (closed) {
			LOGGER.log(Level.WARNING, "commit() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		// !!!!!!!!!!!!!! NO-OP !!!!!!!!!!!!!!!!!!!
	}

	@Override
	public Array createArrayOf(final String arg0, final Object[] arg1) throws SQLException {
		LOGGER.log(Level.WARNING, "createArrayOf() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public Blob createBlob() throws SQLException {
		LOGGER.log(Level.WARNING, "createBlob() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public Clob createClob() throws SQLException {
		LOGGER.log(Level.WARNING, "createClob() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public NClob createNClob() throws SQLException {
		LOGGER.log(Level.WARNING, "createNClob() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		LOGGER.log(Level.WARNING, "createSQLXML() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public Statement createStatement() throws SQLException {
		LOGGER.log(Level.INFO, "Called createStatement()");
		if (closed) {
			LOGGER.log(Level.WARNING, "createStatement() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (oneShotForce) {
			oneShotForce = false; // The statement inherits our one shot
			return new XGStatement(this, force, true);
		} else {
			return new XGStatement(this, force, false);
		}
	}

	@Override
	public Statement createStatement(final int arg0, final int arg1) throws SQLException {
		LOGGER.log(Level.INFO, "Called createStatement()");
		if (closed) {
			LOGGER.log(Level.WARNING, "createStatement() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (oneShotForce) {
			oneShotForce = false; // Statement inherits our one shot
			return new XGStatement(this, arg0, arg1, force, true);
		} else {
			return new XGStatement(this, arg0, arg1, force, false);
		}
	}

	@Override
	public Statement createStatement(final int arg0, final int arg1, final int arg2) throws SQLException {
		LOGGER.log(Level.INFO, "Called createStatement()");
		if (closed) {
			LOGGER.log(Level.WARNING, "createStatement() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (oneShotForce) {
			oneShotForce = false; // Statement inherits our one shot
			return new XGStatement(this, arg0, arg1, arg2, force, true);
		} else {
			return new XGStatement(this, arg0, arg1, arg2, force, false);
		}
	}

	@Override
	public Struct createStruct(final String arg0, final Object[] arg1) throws SQLException {
		LOGGER.log(Level.WARNING, "createStruct() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public boolean getAutoCommit() throws SQLException {
		LOGGER.log(Level.INFO, "Called getAutoCommit()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getAutoCommit() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}
		return true;
	}

	@Override
	public String getCatalog() throws SQLException {
		LOGGER.log(Level.INFO, "Called getCatalog()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getCatalog() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}
		return null;
	}

	@Override
	public Properties getClientInfo() throws SQLException {
		LOGGER.log(Level.INFO, "Called getClientInfo()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getClientInfo() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		return new Properties();
	}

	@Override
	public String getClientInfo(final String arg0) throws SQLException {
		LOGGER.log(Level.INFO, "Called getClientInfo()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getClientInfo() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		return null;
	}

	public String getDB() {
		return database;
	}

	@Override
	public int getHoldability() throws SQLException {
		LOGGER.log(Level.INFO, "Called getHoldability()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getHoldability() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		return ResultSet.CLOSE_CURSORS_AT_COMMIT;
	}

	private int getLength() throws Exception {
		final byte[] inMsg = new byte[4];

		int count = 0;
		while (count < 4) {
			final int temp = in.read(inMsg, count, 4 - count);
			if (temp == -1) {
				throw new IOException();
			}

			count += temp;
		}

		return bytesToInt(inMsg);
	}

	public int getMajorVersion() {
		return Integer.parseInt(driverVersion.substring(0, driverVersion.indexOf(".")));
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		LOGGER.log(Level.INFO, "Called getMetaData()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getMetaData() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		return new XGDatabaseMetaData(this);
	}

	public int getMinorVersion() {
		final int i = driverVersion.indexOf(".") + 1;
		return Integer.parseInt(driverVersion.substring(i, driverVersion.indexOf(".", i)));
	}

	@Override
	public int getNetworkTimeout() throws SQLException {
		LOGGER.log(Level.INFO, "Called getNetworkTimeout()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getNetworkTimeout() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}
		return networkTimeout;
	}

	@Override
	public String getSchema() throws SQLException {
		LOGGER.log(Level.INFO, "Called getSchema()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getSchema() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}
		try {
			return getSchemaFromServer();
		} catch (final Exception e) {
			LOGGER.log(Level.WARNING,
					String.format("Exception %s occurred during getSchema() with message %s", e.toString(), e.getMessage()));
			if (e instanceof SQLException) {
				throw (SQLException) e;
			} else {
				throw SQLStates.newGenericException(e);
			}
		}
	}

	private String getSchemaFromServer() throws Exception {
		// send request
		final ClientWireProtocol.GetSchema.Builder builder = ClientWireProtocol.GetSchema.newBuilder();
		final GetSchema msg = builder.build();
		final ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
		b2.setType(ClientWireProtocol.Request.RequestType.GET_SCHEMA);
		b2.setGetSchema(msg);
		final Request wrapper = b2.build();

		try {
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();
		} catch (final IOException | NullPointerException e) {
			if (!setSchema.equals("")) {
				return setSchema;
			} else {
				reconnect();
				return getSchemaFromServer();
			}
		}

		// get response
		final ClientWireProtocol.GetSchemaResponse.Builder gsr = ClientWireProtocol.GetSchemaResponse.newBuilder();

		try {
			final int length = getLength();
			final byte[] data = new byte[length];
			readBytes(data);
			gsr.mergeFrom(data);
		} catch (SQLException | IOException e) {
			if (e instanceof SQLException && !SQLStates.UNEXPECTED_EOF.equals((SQLException) e)) {
				throw e;
			}

			if (!setSchema.equals("")) {
				return setSchema;
			} else {
				reconnect();
				return getSchemaFromServer();
			}
		}

		final ConfirmationResponse response = gsr.getResponse();
		final ResponseType rType = response.getType();
		processResponseType(rType, response);
		LOGGER.log(Level.INFO, String.format("Got schema: %s from server", gsr.getSchema()));
		return gsr.getSchema();
	}

	private void getStandardResponse() throws Exception {
		final int length = getLength();
		final byte[] data = new byte[length];
		readBytes(data);
		final ConfirmationResponse.Builder rBuild = ConfirmationResponse.newBuilder();
		rBuild.mergeFrom(data);
		final ResponseType rType = rBuild.getType();
		processResponseType(rType, rBuild.build());
	}

	@Override
	public int getTransactionIsolation() throws SQLException {
		LOGGER.log(Level.INFO, "Called getTransactionIsolation()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getTransactionIsolation() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		return Connection.TRANSACTION_NONE;
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		return typeMap;
	}

	public String getURL() {
		return url;
	}

	public String getUser() {
		return user;
	}

	public String getVersion() {
		return driverVersion;
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		LOGGER.log(Level.INFO, "Called getWarnings()");
		if (closed) {
			LOGGER.log(Level.WARNING, "getWarnings() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (warnings.size() == 0) {
			return null;
		}

		final SQLWarning retval = warnings.get(0);
		SQLWarning current = retval;
		int i = 1;
		while (i < warnings.size()) {
			current.setNextWarning(warnings.get(i));
			current = warnings.get(i);
			i++;
		}

		return retval;
	}

	@Override
	public boolean isClosed() throws SQLException {
		LOGGER.log(Level.INFO, "Called isClosed()");
		if (closed) {
			LOGGER.log(Level.INFO, "Returning true from isClosed()");
		} else {
			LOGGER.log(Level.INFO, "Returning false from isClosed()");
		}

		return closed;
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		LOGGER.log(Level.INFO, "Called isReadOnly()");
		if (closed) {
			LOGGER.log(Level.WARNING, "isReadOnly() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		return false;
	}

	@Override
	public boolean isValid(final int arg0) throws SQLException {
		LOGGER.log(Level.INFO, "Called isValid()");
		if (arg0 < 0) {
			LOGGER.log(Level.WARNING, "isValid() is throwing INVALID_ARGUMENT");
			throw SQLStates.INVALID_ARGUMENT.clone();
		}

		if (closed) {
			LOGGER.log(Level.WARNING, "Returning false from isValid() because connection is closed");
			return false;
		}

		boolean retval = false;
		try {
			XGConnection clone = copy();
			retval = copy().testConnection(arg0);
			clone.close();
		} catch (Exception e) {
		}

		if (!retval) {
			LOGGER.log(Level.SEVERE, "Returning false from isValid() because connection test failed");
		}

		return retval;
	}

	@Override
	public boolean isWrapperFor(final Class<?> iface) throws SQLException {
		LOGGER.log(Level.INFO, "Called isWrapperFor()");
		return false;
	}

	@Override
	public String nativeSQL(final String arg0) throws SQLException {
		LOGGER.log(Level.INFO, "Called nativeSQL()");
		if (closed) {
			LOGGER.log(Level.WARNING, "nativeSQL() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		return arg0;
	}

	@Override
	public CallableStatement prepareCall(final String arg0) throws SQLException {
		LOGGER.log(Level.WARNING, "prepareCall() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public CallableStatement prepareCall(final String arg0, final int arg1, final int arg2) throws SQLException {
		LOGGER.log(Level.WARNING, "prepareCall() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public CallableStatement prepareCall(final String arg0, final int arg1, final int arg2, final int arg3)
			throws SQLException {
		LOGGER.log(Level.WARNING, "prepareCall() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public PreparedStatement prepareStatement(final String arg0) throws SQLException {
		LOGGER.log(Level.INFO, "Called prepareStatement()");
		if (closed) {
			LOGGER.log(Level.WARNING, "prepareStatement() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (oneShotForce) {
			oneShotForce = false; // Statement inherits our one shot
			return new XGPreparedStatement(this, arg0, force, true);
		} else {
			return new XGPreparedStatement(this, arg0, force, false);
		}
	}

	@Override
	public PreparedStatement prepareStatement(final String arg0, final int arg1) throws SQLException {
		LOGGER.log(Level.WARNING, "prepareStatement() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public PreparedStatement prepareStatement(final String arg0, final int arg1, final int arg2) throws SQLException {
		LOGGER.log(Level.INFO, "Called prepareStatement()");
		if (closed) {
			LOGGER.log(Level.WARNING, "prepareStatement() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (oneShotForce) {
			oneShotForce = false; // Statement inherits our one shot
			return new XGPreparedStatement(this, arg0, arg1, arg2, force, true);
		} else {
			return new XGPreparedStatement(this, arg0, arg1, arg2, force, false);
		}
	}

	@Override
	public PreparedStatement prepareStatement(final String arg0, final int arg1, final int arg2, final int arg3)
			throws SQLException {
		LOGGER.log(Level.INFO, "Called prepareStatement()");
		if (closed) {
			LOGGER.log(Level.WARNING, "prepareStatement() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (oneShotForce) {
			oneShotForce = false; // Statement inherits our one shot
			return new XGPreparedStatement(this, arg0, arg1, arg2, arg3, force, true);
		} else {
			return new XGPreparedStatement(this, arg0, arg1, arg2, arg3, force, false);
		}
	}

	@Override
	public PreparedStatement prepareStatement(final String arg0, final int[] arg1) throws SQLException {
		LOGGER.log(Level.WARNING, "prepareStatement() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public PreparedStatement prepareStatement(final String arg0, final String[] arg1) throws SQLException {
		LOGGER.log(Level.WARNING, "prepareStatement() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	private void processResponseType(final ResponseType rType, final ConfirmationResponse response)
			throws SQLException {
		if (rType.equals(ResponseType.INVALID)) {
			LOGGER.log(Level.WARNING, "Server returned an invalid response");
			throw SQLStates.INVALID_RESPONSE_TYPE.clone();
		} else if (rType.equals(ResponseType.RESPONSE_ERROR)) {
			final String reason = response.getReason();
			final String sqlState = response.getSqlState();
			final int code = response.getVendorCode();
			LOGGER.log(Level.WARNING, String.format("Server returned an error response [%s] %s", sqlState, reason));
			throw new SQLException(reason, sqlState, code);
		} else if (rType.equals(ResponseType.RESPONSE_WARN)) {
			final String reason = response.getReason();
			final String sqlState = response.getSqlState();
			final int code = response.getVendorCode();
			warnings.add(new SQLWarning(reason, sqlState, code));
		}
	}

	private void readBytes(final byte[] data) throws Exception {
		final int z = data.length;
		int count = 0;
		while (count < z) {
			final int temp = in.read(data, count, z - count);
			if (temp == -1) {
				throw new IOException();
			}

			count += temp;
		}

		return;
	}

	/*
	 * Is the connection currently connected?
	 */
	public boolean connected() {
		return connected;
	}

	public void reconnect() throws IOException, SQLException
	{
		reconnect(false);
	}

	/*
	 * We seem to have lost our connection. Reconnect to any cmdcomp
	 */
	public void reconnect(final boolean shouldRequestVersion) throws IOException, SQLException {
		// Try to find any cmdcomp that we can connect to
		// If we can't connect to any throw IOException

		// There's an issue here that we don't want to force
		// But we could get redirected back to the dead node
		// Until the heartbeat timeout happens
		// Which could be up to 30 seconds
		// If the redirect fails, it will call reconnect
		// And we will end up looping until the heartbeat times out
		// In 30 seconds, we would totally blow out the stack

		// Even forcing, it only guarantees the first request after reconnect
		// is forced, which if the client is making a fast series of short
		// requests, puts us in the same situation

		// We solve this by delaying slightly, which will slow the rate
		// of stack growth enough that we will be ok
		LOGGER.log(Level.INFO, String.format("Entered reconnect() with shouldRequestVersion: %b", shouldRequestVersion));
		try {
			Thread.sleep(250);
		} catch (final InterruptedException e) {
		}

		try {
			if (in != null) {
				in.close();
			}

			if (out != null) {
				out.close();
			}

			if (sock != null) {
				sock.close();
			}
		} catch (final IOException e) {
		}

		if (force) {
			LOGGER.log(Level.INFO, "Forced reconnection.");
			sock = null;
			try {
				connect(this.ip, this.portNum);
			} catch (final Exception e) {
				// reconnect failed so we are no longer connected
				connected = false;

				LOGGER.log(Level.WARNING,
						String.format("Exception %s occurred in reconnect() with message %s", e.toString(), e.getMessage()));
				if (e instanceof IOException) {
					throw (IOException) e;
				}

				throw new IOException();
			}

			try {
				clientHandshake(user, pwd, database, shouldRequestVersion);
				if (!setSchema.equals("")) {
					setSchema(setSchema);
				}

				if (setPso == -1) {
					// We have to turn it off
					setPSO(false);
				} else if (setPso > 0) {
					// Set non-default threshold
					setPSO(setPso);
				}

				return;
			} catch (final Exception handshakeException) {
				try {
					in.close();
					out.close();
					sock.close();
				} catch (final IOException f) {
				}

				// reconnect failed so we are no longer connected
				connected = false;

				// Failed on the client handshake, so capture exception
				if (handshakeException instanceof SQLException) {
					throw (SQLException) handshakeException;
				}

				throw new IOException();
			}
		}

		// capture any exception from trying to connect
		SQLException retVal = null;
		if (secondaryIndex != -1)
		{
			LOGGER.log(Level.INFO, "reconnect() Trying secondary interfaces");
			for (ArrayList<String> list : secondaryInterfaces)
			{
				String cmdcomp = list.get(secondaryIndex);
				final StringTokenizer tokens = new StringTokenizer(cmdcomp, ":", false);
				final String host = tokens.nextToken();
				final int port = Integer.parseInt(tokens.nextToken());

				// Try to connect to this one
				this.ip = host;

				sock = null;
				try {
					connect(host, port);
				} catch (final Exception e) {
					LOGGER.log(Level.WARNING,
							String.format("Exception %s occurred in reconnect() with message %s", e.toString(), e.getMessage()));
					continue;
				}

				this.portNum = port;
				try {
					clientHandshake(user, pwd, database, shouldRequestVersion);
					if (!setSchema.equals("")) {
						setSchema(setSchema);
					}

					if (setPso == -1) {
						// We have to turn it off
						setPSO(false);
					} else if (setPso > 0) {
						// Set non-default threshold
						setPSO(setPso);
					}

					if (setPso == -1) {
						// We have to turn it off
						setPSO(false);
					} else if (setPso > 0) {
						// Set non-default threshold
						setPSO(setPso);
					}

					return;
				} catch (final Exception handshakeException) {
					try {
						in.close();
						out.close();
						sock.close();
					} catch (final IOException f) {
					}
					// Failed on the client handshake, so capture exception
					if (handshakeException instanceof SQLException) {
						retVal = (SQLException) handshakeException;
					}
				}
				// reconnect failed so we are no longer connected
				connected = false;
			}
		}
		
		//We should just try them all
		for (ArrayList<String> list : secondaryInterfaces)
		{
			LOGGER.log(Level.WARNING, "Trying secondary interfaces again");
			int index = 0;
			for (String cmdcomp : list)
			{
				final StringTokenizer tokens = new StringTokenizer(cmdcomp, ":", false);
				final String host = tokens.nextToken();
				final int port = Integer.parseInt(tokens.nextToken());
	
				// Try to connect to this one
				this.ip = host;
	
				sock = null;
				try {
					connect(host, port);
				} catch (final Exception e) {
					LOGGER.log(Level.WARNING,
							String.format("Exception %s occurred in reconnect() with message %s", e.toString(), e.getMessage()));
					index++;
					continue;
				}
	
				this.portNum = port;
				try {
					clientHandshake(user, pwd, database, shouldRequestVersion);
					if (!setSchema.equals("")) {
						setSchema(setSchema);
					}
	
					if (setPso == -1) {
						// We have to turn it off
						setPSO(false);
					} else if (setPso > 0) {
						// Set non-default threshold
						setPSO(setPso);
					}
	
					if (setPso == -1) {
						// We have to turn it off
						setPSO(false);
					} else if (setPso > 0) {
						// Set non-default threshold
						setPSO(setPso);
					}
	
					secondaryIndex = index;
					return;
				} catch (final Exception handshakeException) {
					try {
						in.close();
						out.close();
						sock.close();
					} catch (final IOException f) {
					}
					// Failed on the client handshake, so capture exception
					if (handshakeException instanceof SQLException) {
						retVal = (SQLException) handshakeException;
					}
				}
				// reconnect failed so we are no longer connected
				connected = false;
				index++;
			}
		}
		
		sock = null;
		this.ip = originalIp;
		this.portNum = originalPort;
		try {
			LOGGER.log(Level.INFO, "reconnect() Trying original IP and port.");
			connect(this.ip, this.portNum);
		} catch (final Exception e) {
			// reconnect failed so we are no longer connected
			connected = false;

			LOGGER.log(Level.WARNING,
					String.format("Exception %s occurred in reconnect() with message %s", e.toString(), e.getMessage()));
			if (e instanceof IOException) {
				throw (IOException) e;
			}

			throw new IOException("Failed to reconnect.");
		}

		try {
			clientHandshake(user, pwd, database, shouldRequestVersion);
			if (!setSchema.equals("")) {
				setSchema(setSchema);
			}

			if (setPso == -1) {
				// We have to turn it off
				setPSO(false);
			} else if (setPso > 0) {
				// Set non-default threshold
				setPSO(setPso);
			}

			return;
		} catch (final Exception handshakeException) {
			try {
				in.close();
				out.close();
				sock.close();
			} catch (final IOException f) {
			}

			// reconnect failed so we are no longer connected
			connected = false;

			// Failed on the client handshake, so capture exception
			if (handshakeException instanceof SQLException) {
				throw (SQLException) handshakeException;
			}

			throw new IOException("Failed to reconnect.");
		}
	}

	/*
	 * We have to told to redirect our request elsewhere.
	 */
	public void redirect(final String host, final int port, boolean shouldRequestVersion) throws IOException, SQLException {
		LOGGER.log(Level.INFO,String.format("redirect(). Getting redirected to host: %s and port: %d", host, port));
		oneShotForce = true;

		// Close current connection
		try {
			in.close();
			out.close();
			sock.close();
		} catch (final IOException e) {
		}
		
		//Figure out the correct interface to use
		boolean tryAllInList = false;
		int listToTry = 0;
		if (secondaryIndex != -1)
		{
			String combined = host + ":" + port;
			int listIndex = 0;
			for (ArrayList<String> list : secondaryInterfaces)
			{
				if (list.get(0).equals(combined))
				{
					break;
				}
				
				listIndex++;
			}
			
			if (listIndex < secondaryInterfaces.size())
			{
				final StringTokenizer tokens = new StringTokenizer(secondaryInterfaces.get(listIndex).get(secondaryIndex), ":", false);
				this.ip = tokens.nextToken();
				this.portNum = Integer.parseInt(tokens.nextToken());
			}
			else
			{
				this.ip = host;
				this.portNum = port;
			}
		}
		else
		{
			String combined = host + ":" + port;
			int listIndex = 0;
			for (ArrayList<String> list : secondaryInterfaces)
			{
				if (list.get(0).equals(combined))
				{
					break;
				}
				
				listIndex++;
			}
			
			if (listIndex < secondaryInterfaces.size())
			{
				tryAllInList = true;
				listToTry = listIndex;
			}
			else
			{
				this.ip = host;
				this.portNum = port;
			}
		}

		if (!tryAllInList)
		{
			sock = null;
			try {
				connect(this.ip, this.portNum);
			} catch (final Exception e) {
	
				LOGGER.log(Level.WARNING,
						String.format("Exception %s occurred in redirect() with message %s", e.toString(), e.getMessage()));
				reconnect();
				return;
			}
	
			try {
				clientHandshake(user, pwd, database, shouldRequestVersion);
				oneShotForce = true;
				if (!setSchema.equals("")) {
					setSchema(setSchema);
				}
	
				if (setPso == -1) {
					// We have to turn it off
					setPSO(false);
				} else if (setPso > 0) {
					// Set non-default threshold
					setPSO(setPso);
				}
	
				if (setPso == -1) {
					// We have to turn it off
					setPSO(false);
				} else if (setPso > 0) {
					// Set non-default threshold
					setPSO(setPso);
				}
	
				return;
			} catch (final Exception e) {
				try {
					in.close();
					out.close();
					sock.close();
				} catch (final IOException f) {
				}
	
				reconnect();
			}
		}
		else
		{
			for (String cmdcomp : secondaryInterfaces.get(listToTry))
			{
				final StringTokenizer tokens = new StringTokenizer(cmdcomp, ":", false);
				this.ip = tokens.nextToken();
				this.portNum = Integer.parseInt(tokens.nextToken());
				
				sock = null;
				try {
					connect(this.ip, this.portNum);
				} catch (final Exception e) {
					LOGGER.log(Level.WARNING,
							String.format("Exception %s occurred in redirect() with message %s", e.toString(), e.getMessage()));
					continue;
				}
		
				try {
					clientHandshake(user, pwd, database, shouldRequestVersion);
					oneShotForce = true;
					if (!setSchema.equals("")) {
						setSchema(setSchema);
					}
		
					if (setPso == -1) {
						// We have to turn it off
						setPSO(false);
					} else if (setPso > 0) {
						// Set non-default threshold
						setPSO(setPso);
					}
		
					if (setPso == -1) {
						// We have to turn it off
						setPSO(false);
					} else if (setPso > 0) {
						// Set non-default threshold
						setPSO(setPso);
					}
		
					return;
				} catch (final Exception e) {
					try {
						in.close();
						out.close();
						sock.close();
					} catch (final IOException f) {
					}
				}
			}
			
			reconnect(); //Everything else failed, so just call reconnect()
		}
	}

	@Override
	public void releaseSavepoint(final Savepoint arg0) throws SQLException {
		LOGGER.log(Level.WARNING, "releaseAvepoint() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();

	}

	@Override
	public void rollback() throws SQLException {
		LOGGER.log(Level.WARNING, "rollback() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public void rollback(final Savepoint arg0) throws SQLException {
		LOGGER.log(Level.WARNING, "rollback() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	private void sendClose() throws Exception {
		// send request
		final ClientWireProtocol.CloseConnection.Builder builder = ClientWireProtocol.CloseConnection.newBuilder();
		final CloseConnection msg = builder.build();
		final ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
		b2.setType(ClientWireProtocol.Request.RequestType.CLOSE_CONNECTION);
		b2.setCloseConnection(msg);
		final Request wrapper = b2.build();

		try {
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();
		} catch (final IOException e) {
			// Who cares...
		}
	}

	private void sendSetSchema(final String schema) throws Exception {
		// send request
		LOGGER.log(Level.INFO, String.format("Sending set schema (%s) request to the server", schema));
		final ClientWireProtocol.SetSchema.Builder builder = ClientWireProtocol.SetSchema.newBuilder();
		builder.setSchema(schema);
		final SetSchema msg = builder.build();
		final ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
		b2.setType(ClientWireProtocol.Request.RequestType.SET_SCHEMA);
		b2.setSetSchema(msg);
		final Request wrapper = b2.build();

		try {
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();
			getStandardResponse();
		} catch (final IOException e) {
			// Doesn't matter...
			LOGGER.log(Level.WARNING,
					String.format("Failed sending set schema request to the server with exception %s with message %s",
							e.toString(), e.getMessage()));
		}

		setSchema = schema;
	}

	public void forceExternal(boolean force) throws Exception {
		LOGGER.log(Level.INFO, "Sending force external request to the server");
		if (closed) {
			LOGGER.log(Level.WARNING, "Force external request is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		// send request
		final ClientWireProtocol.ForceExternal.Builder builder = ClientWireProtocol.ForceExternal.newBuilder();
		builder.setForce(force);
		final ForceExternal msg = builder.build();
		final ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
		b2.setType(ClientWireProtocol.Request.RequestType.FORCE_EXTERNAL);
		b2.setForceExternal(msg);
		final Request wrapper = b2.build();

		try {
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();
			getStandardResponse();
		} catch (final IOException e) {
			// Doesn't matter...
			LOGGER.log(Level.WARNING,
					String.format("Failed sending set schema request to the server with exception %s with message %s",
							e.toString(), e.getMessage()));
		}
	}

	// sets the pso threshold on this connection to threshold
	public void setPSO(long threshold) throws Exception {
		LOGGER.log(Level.INFO, "Sending set pso request to the server");
		if (closed) {
			LOGGER.log(Level.WARNING, "Set pso request is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		// send request
		setPso = threshold;
		final ClientWireProtocol.SetPSO.Builder builder = ClientWireProtocol.SetPSO.newBuilder();
		builder.setThreshold(threshold);
		builder.setReset(false);
		final SetPSO msg = builder.build();
		final ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
		b2.setType(ClientWireProtocol.Request.RequestType.SET_PSO);
		b2.setSetPso(msg);
		final Request wrapper = b2.build();

		try {
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();
			getStandardResponse();
		} catch (final IOException e) {
			// Doesn't matter...
			LOGGER.log(Level.WARNING,
					String.format("Failed sending set pso request to the server with exception %s with message %s", e,
							e.getMessage()));
		}
	}

	// sets the pso threshold on this connection to be -1(meaning pso is turned off)
	// or back to the default
	public void setPSO(boolean on) throws Exception {
		LOGGER.log(Level.INFO, "Sending set pso request to the server");
		if (closed) {
			LOGGER.log(Level.WARNING, "Set pso request is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (on) {
			setPso = 0;
		} else {
			setPso = -1;
		}

		// send request
		final ClientWireProtocol.SetPSO.Builder builder = ClientWireProtocol.SetPSO.newBuilder();
		builder.setThreshold(-1);
		builder.setReset(on);
		final SetPSO msg = builder.build();
		final ClientWireProtocol.Request.Builder b2 = ClientWireProtocol.Request.newBuilder();
		b2.setType(ClientWireProtocol.Request.RequestType.SET_PSO);
		b2.setSetPso(msg);
		final Request wrapper = b2.build();

		try {
			out.write(intToBytes(wrapper.getSerializedSize()));
			wrapper.writeTo(out);
			out.flush();
			getStandardResponse();
		} catch (final IOException e) {
			// Doesn't matter...
			LOGGER.log(Level.WARNING, String.format(
					"Failed sending set pso request to the server with exception %s with message ", e.toString(), e.getMessage()));
		}
	}

	@Override
	public void setAutoCommit(final boolean arg0) throws SQLException {
		LOGGER.log(Level.WARNING, "Called setAutoCommit()");
	}

	@Override
	public void setCatalog(final String arg0) throws SQLException {
		LOGGER.log(Level.WARNING, "Called setCatalog()");
	}

	@Override
	public void setClientInfo(final Properties arg0) throws SQLClientInfoException {
		LOGGER.log(Level.WARNING, "Called setClientInfo()");
	}

	@Override
	public void setClientInfo(final String arg0, final String arg1) throws SQLClientInfoException {
		LOGGER.log(Level.WARNING, "Called setClientInfo()");
	}

	@Override
	public void setHoldability(final int arg0) throws SQLException {
		LOGGER.log(Level.INFO, "Called setHoldability()");
		if (arg0 != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
			LOGGER.log(Level.WARNING, "setHoldability() is throwing SQLFeatureNotSupportedException");
			throw new SQLFeatureNotSupportedException();
		}
	}

	@Override
	public void setNetworkTimeout(final Executor executor, final int milliseconds) throws SQLException {
		LOGGER.log(Level.WARNING, "Called setNetworkTimeout()");
		if (closed) {
			LOGGER.log(Level.WARNING, "setNetworkTimeout() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}
		
		networkTimeout = milliseconds;
	}

	@Override
	public void setReadOnly(final boolean arg0) throws SQLException {
		LOGGER.log(Level.WARNING, "Called setReadOnly()");
		if (closed) {
			LOGGER.log(Level.WARNING, "setReadOnly() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		LOGGER.log(Level.WARNING, "setSavepoint() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public Savepoint setSavepoint(final String arg0) throws SQLException {
		LOGGER.log(Level.WARNING, "setSavepoint() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public void setSchema(final String schema) throws SQLException {
		LOGGER.log(Level.INFO, "Called setSchema()");
		if (closed) {
			LOGGER.log(Level.WARNING, "setSchema() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		try {
			sendSetSchema(schema);
		} catch (final Exception e) {
			LOGGER.log(Level.WARNING,
					String.format("Exception %s occurred during setSchema() with message %s", e.toString(), e.getMessage()));
			if (e instanceof SQLException) {
				throw (SQLException) e;
			} else {
				throw SQLStates.newGenericException(e);
			}
		}
	}

	@Override
	public void setTransactionIsolation(final int arg0) throws SQLException {
		LOGGER.log(Level.INFO, "Called setTransactionIsolation()");
		if (closed) {
			LOGGER.log(Level.WARNING, "setTransactionIsolation() is throwing CALL_ON_CLOSED_OBJECT");
			throw SQLStates.CALL_ON_CLOSED_OBJECT.clone();
		}

		if (arg0 != Connection.TRANSACTION_NONE) {
			LOGGER.log(Level.WARNING, "setTransactionIsolation() is throwing SQLFeatureNotSupportedException");
			throw new SQLFeatureNotSupportedException();
		}
	}

	@Override
	public void setTypeMap(final Map<String, Class<?>> arg0) throws SQLException {
		LOGGER.log(Level.INFO, "Called setTypeMap()");
		typeMap = arg0;
	}

	private boolean testConnection(final int timeoutSecs) {
		final TestConnectionThread thread = new TestConnectionThread();
		thread.start();
		try {
			thread.join(timeoutSecs * 1000);
		} catch (final Exception e) {
		}

		if (thread.isAlive()) {
			return false;
		}

		if (thread.e != null) {
			return false;
		}

		return true;
	}

	@Override
	public <T> T unwrap(final Class<T> iface) throws SQLException {
		LOGGER.log(Level.WARNING, "setSavepoint() was called, which is not supported");
		throw new SQLFeatureNotSupportedException();
	}
}

