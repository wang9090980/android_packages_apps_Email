/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.mail.transport;

import com.android.email.Email;
import com.android.email.codec.binary.Base64;
import com.android.email.mail.Address;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.CertificateValidationException;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Sender;
import com.android.email.mail.Transport;
import com.android.email.provider.EmailContent.Message;

import android.content.Context;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

import javax.net.ssl.SSLException;

/**
 * This class handles all of the protocol-level aspects of sending messages via SMTP.
 */
public class SmtpSender extends Sender {

    Context mContext;
    private Transport mTransport;
    String mUsername;
    String mPassword;

    /**
     * Static named constructor.
     */
    public static Sender newInstance(Context context, String uri) throws MessagingException {
        return new SmtpSender(context, uri);
    }

    /**
     * Allowed formats for the Uri:
     * smtp://user:password@server:port
     * smtp+tls+://user:password@server:port
     * smtp+tls+trustallcerts://user:password@server:port
     * smtp+ssl+://user:password@server:port
     * smtp+ssl+trustallcerts://user:password@server:port
     *
     * @param uriString the Uri containing information to configure this sender
     */
    private SmtpSender(Context context, String uriString) throws MessagingException {
        mContext = context;
        URI uri;
        try {
            uri = new URI(uriString);
        } catch (URISyntaxException use) {
            throw new MessagingException("Invalid SmtpTransport URI", use);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.startsWith("smtp")) {
            throw new MessagingException("Unsupported protocol");
        }
        // defaults, which can be changed by security modifiers
        int connectionSecurity = Transport.CONNECTION_SECURITY_NONE;
        int defaultPort = 587;
        // check for security modifiers and apply changes
        if (scheme.contains("+ssl")) {
            connectionSecurity = Transport.CONNECTION_SECURITY_SSL;
            defaultPort = 465;
        } else if (scheme.contains("+tls")) {
            connectionSecurity = Transport.CONNECTION_SECURITY_TLS;
        }
        boolean trustCertificates = scheme.contains("+trustallcerts");

        mTransport = new MailTransport("SMTP");
        mTransport.setUri(uri, defaultPort);
        mTransport.setSecurity(connectionSecurity, trustCertificates);

        String[] userInfoParts = mTransport.getUserInfoParts();
        if (userInfoParts != null) {
            mUsername = userInfoParts[0];
            if (userInfoParts.length > 1) {
                mPassword = userInfoParts[1];
            }
        }
    }

    /**
     * For testing only.  Injects a different transport.  The transport should already be set
     * up and ready to use.  Do not use for real code.
     * @param testTransport The Transport to inject and use for all future communication.
     */
    /* package */ void setTransport(Transport testTransport) {
        mTransport = testTransport;
    }

    @Override
    public void open() throws MessagingException {
        try {
            mTransport.open();

            // Eat the banner
            executeSimpleCommand(null);

            String localHost = "localhost";
            try {
                InetAddress localAddress = InetAddress.getLocalHost();
                localHost = localAddress.getHostName();
            } catch (Exception e) {
                if (Config.LOGD && Email.DEBUG) {
                    Log.d(Email.LOG_TAG, "Unable to look up localhost");
                }
            }

            String result = executeSimpleCommand("EHLO " + localHost);

            /*
             * TODO may need to add code to fall back to HELO I switched it from
             * using HELO on non STARTTLS connections because of AOL's mail
             * server. It won't let you use AUTH without EHLO.
             * We should really be paying more attention to the capabilities
             * and only attempting auth if it's available, and warning the user
             * if not.
             */
            if (mTransport.canTryTlsSecurity()) {
                if (result.contains("-STARTTLS") || result.contains(" STARTTLS")) {
                    executeSimpleCommand("STARTTLS");
                    mTransport.reopenTls();
                    /*
                     * Now resend the EHLO. Required by RFC2487 Sec. 5.2, and more specifically,
                     * Exim.
                     */
                    result = executeSimpleCommand("EHLO " + localHost);
                } else {
                    if (Config.LOGD && Email.DEBUG) {
                        Log.d(Email.LOG_TAG, "TLS not supported but required");
                    }
                    throw new MessagingException(MessagingException.TLS_REQUIRED);
                }
            }

            /*
             * result contains the results of the EHLO in concatenated form
             */
            boolean authLoginSupported = result.matches(".*AUTH.*LOGIN.*$");
            boolean authPlainSupported = result.matches(".*AUTH.*PLAIN.*$");

            if (mUsername != null && mUsername.length() > 0 && mPassword != null
                    && mPassword.length() > 0) {
                if (authPlainSupported) {
                    saslAuthPlain(mUsername, mPassword);
                }
                else if (authLoginSupported) {
                    saslAuthLogin(mUsername, mPassword);
                }
                else {
                    if (Config.LOGD && Email.DEBUG) {
                        Log.d(Email.LOG_TAG, "No valid authentication mechanism found.");
                    }
                    throw new MessagingException(MessagingException.AUTH_REQUIRED);
                }
            }
        } catch (SSLException e) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, e.toString());
            }
            throw new CertificateValidationException(e.getMessage(), e);
        } catch (IOException ioe) {
            if (Config.LOGD && Email.DEBUG) {
                Log.d(Email.LOG_TAG, ioe.toString());
            }
            throw new MessagingException(MessagingException.IOERROR, ioe.toString());
        }
    }

    @Override
    public void sendMessage(long messageId) throws MessagingException {
        close();
        open();

        Message message = Message.restoreMessageWithId(mContext, messageId);
        if (message == null) {
            throw new MessagingException("Trying to send non-existent message id="
                    + Long.toString(messageId));
        }
        Address from = Address.unpackFirst(message.mFrom);
        Address[] to = Address.unpack(message.mTo);
        Address[] cc = Address.unpack(message.mCc);
        Address[] bcc = Address.unpack(message.mBcc);

        try {
            executeSimpleCommand("MAIL FROM: " + "<" + from.getAddress() + ">");
            for (Address address : to) {
                executeSimpleCommand("RCPT TO: " + "<" + address.getAddress() + ">");
            }
            for (Address address : cc) {
                executeSimpleCommand("RCPT TO: " + "<" + address.getAddress() + ">");
            }
            for (Address address : bcc) {
                executeSimpleCommand("RCPT TO: " + "<" + address.getAddress() + ">");
            }
            executeSimpleCommand("DATA");
            // TODO byte stuffing
            Rfc822Output.writeTo(mContext, messageId,
                    new EOLConvertingOutputStream(mTransport.getOutputStream()), true, false);
            executeSimpleCommand("\r\n.");
        } catch (IOException ioe) {
            throw new MessagingException("Unable to send message", ioe);
        }
    }

    /**
     * Close the protocol (and the transport below it).
     *
     * MUST NOT return any exceptions.
     */
    @Override
    public void close() {
        mTransport.close();
    }

    /**
     * Send a single command and wait for a single response.  Handles responses that continue
     * onto multiple lines.  Throws MessagingException if response code is 4xx or 5xx.  All traffic
     * is logged (if debug logging is enabled) so do not use this function for user ID or password.
     *
     * @param command The command string to send to the server.
     * @return Returns the response string from the server.
     */
    private String executeSimpleCommand(String command) throws IOException, MessagingException {
        return executeSensitiveCommand(command, null);
    }

    /**
     * Send a single command and wait for a single response.  Handles responses that continue
     * onto multiple lines.  Throws MessagingException if response code is 4xx or 5xx.
     *
     * @param command The command string to send to the server.
     * @param sensitiveReplacement If the command includes sensitive data (e.g. authentication)
     * please pass a replacement string here (for logging).
     * @return Returns the response string from the server.
     */
    private String executeSensitiveCommand(String command, String sensitiveReplacement)
            throws IOException, MessagingException {
        if (command != null) {
            mTransport.writeLine(command, sensitiveReplacement);
        }

        String line = mTransport.readLine();

        String result = line;

        while (line.length() >= 4 && line.charAt(3) == '-') {
            line = mTransport.readLine();
            result += line.substring(3);
        }

        if (result.length() > 0) {
            char c = result.charAt(0);
            if ((c == '4') || (c == '5')) {
                throw new MessagingException(result);
            }
        }

        return result;
    }


//    C: AUTH LOGIN
//    S: 334 VXNlcm5hbWU6
//    C: d2VsZG9u
//    S: 334 UGFzc3dvcmQ6
//    C: dzNsZDBu
//    S: 235 2.0.0 OK Authenticated
//
//    Lines 2-5 of the conversation contain base64-encoded information. The same conversation, with base64 strings decoded, reads:
//
//
//    C: AUTH LOGIN
//    S: 334 Username:
//    C: weldon
//    S: 334 Password:
//    C: w3ld0n
//    S: 235 2.0.0 OK Authenticated

    private void saslAuthLogin(String username, String password) throws MessagingException,
        AuthenticationFailedException, IOException {
        try {
            executeSimpleCommand("AUTH LOGIN");
            executeSensitiveCommand(new String(Base64.encodeBase64(username.getBytes())),
                    "/username redacted/");
            executeSensitiveCommand(new String(Base64.encodeBase64(password.getBytes())),
                    "/password redacted/");
        }
        catch (MessagingException me) {
            if (me.getMessage().length() > 1 && me.getMessage().charAt(1) == '3') {
                throw new AuthenticationFailedException(me.getMessage());
            }
            throw me;
        }
    }

    private void saslAuthPlain(String username, String password) throws MessagingException,
            AuthenticationFailedException, IOException {
        byte[] data = ("\000" + username + "\000" + password).getBytes();
        data = new Base64().encode(data);
        try {
            executeSensitiveCommand("AUTH PLAIN " + new String(data), "AUTH PLAIN /redacted/");
        }
        catch (MessagingException me) {
            if (me.getMessage().length() > 1 && me.getMessage().charAt(1) == '3') {
                throw new AuthenticationFailedException(me.getMessage());
            }
            throw me;
        }
    }
}
