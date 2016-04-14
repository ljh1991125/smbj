/*
 * Copyright (C)2016 - Jeroen van Erp <jeroen@hierynomus.com>
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
package com.hierynomus.smbj.auth;

import com.hierynomus.ntlm.NtlmException;
import com.hierynomus.ntlm.functions.NtlmFunctions;
import com.hierynomus.ntlm.messages.NtlmChallenge;
import com.hierynomus.ntlm.messages.NtlmChallengeResponse;
import com.hierynomus.ntlm.messages.NtlmNegotiate;
import com.hierynomus.ntlm.messages.NtlmNegotiateFlag;
import com.hierynomus.ntlm.messages.NtlmPacket;
import com.hierynomus.protocol.commons.ByteArrayUtils;
import com.hierynomus.protocol.commons.buffer.Buffer;
import com.hierynomus.protocol.commons.buffer.Endian;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.smb2.SMB2Packet;
import com.hierynomus.smbj.smb2.SMB2StatusCode;
import com.hierynomus.smbj.smb2.messages.SMB2SessionSetup;
import com.hierynomus.smbj.transport.TransportException;
import com.hierynomus.spnego.NegTokenInit;
import com.hierynomus.spnego.NegTokenTarg;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.microsoft.MicrosoftObjectIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.EnumSet;

public class NtlmAuthenticator implements Authenticator {
    private static final Logger logger = LoggerFactory.getLogger(NtlmAuthenticator.class);

    private static final ASN1ObjectIdentifier NTLMSSP = MicrosoftObjectIdentifiers.microsoft.branch("2.2.10");

    public static class Factory implements com.hierynomus.protocol.commons.Factory.Named<NtlmAuthenticator> {
        @Override
        public String getName() {
            // The OID for NTLMSSP
            return "1.3.6.1.4.1.311.2.2.10";
        }

        @Override
        public NtlmAuthenticator create() {
            return new NtlmAuthenticator();
        }
    }

    public long authenticate(Connection connection, AuthenticationContext context) throws TransportException {
        try {
            logger.info("Authenticating {} on {} using NTLM", context.getUsername(), connection.getRemoteHostname());
            EnumSet<SMB2SessionSetup.SMB2SecurityMode> signingEnabled = EnumSet.of
                    (SMB2SessionSetup.SMB2SecurityMode.SMB2_NEGOTIATE_SIGNING_ENABLED);

            SMB2SessionSetup smb2SessionSetup = new SMB2SessionSetup(connection.getNegotiatedDialect(), signingEnabled);
            NtlmNegotiate ntlmNegotiate = new NtlmNegotiate();
            byte[] asn1 = negTokenInit(ntlmNegotiate);
            smb2SessionSetup.setSecurityBuffer(asn1);
            connection.send(smb2SessionSetup);
            SMB2SessionSetup receive = (SMB2SessionSetup) connection.receive();
            long sessionId = receive.getHeader().getSessionId();
            if (receive.getHeader().getStatus() == SMB2StatusCode.STATUS_MORE_PROCESSING_REQUIRED) {
                logger.debug("More processing required for authentication of {}", context.getUsername());
                byte[] securityBuffer = receive.getSecurityBuffer();
                logger.info("Received token: {}", ByteArrayUtils.printHex(securityBuffer));

                NegTokenTarg negTokenTarg = new NegTokenTarg().read(securityBuffer);
                BigInteger negotiationResult = negTokenTarg.getNegotiationResult();
                NtlmChallenge challenge = (NtlmChallenge) new NtlmChallenge().read(new Buffer.PlainBuffer(negTokenTarg.getResponseToken(), Endian.LE));
                logger.debug("Received NTLM challenge from: {}", challenge.getTargetName());

                byte[] serverChallenge = challenge.getServerChallenge();

                byte[] responseKeyNT = NtlmFunctions.NTOWFv2(
                        String.valueOf(context.getPassword()),
                        context.getUsername(),
                        context.getDomain());

                byte[] challengeFromClient = new byte[8];
                NtlmFunctions.RANDOM.nextBytes(challengeFromClient);

                byte[] ntlmv2ClientChallenge =
                        NtlmFunctions.getNTLMv2ClientChallenge(challengeFromClient, challenge.getTargetInfo());

                byte[] ntlmv2Response = NtlmFunctions.getNTLMv2Response(responseKeyNT, serverChallenge, ntlmv2ClientChallenge);

                byte[] sessionkey = null;

                if (challenge.getNegotiateFlags().contains(NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_SIGN)) {
                    byte[] userSessionKey = NtlmFunctions.hmac_md5(
                            responseKeyNT, ByteBuffer.wrap(ntlmv2Response, 0, 16).array());

                    if ((challenge.getNegotiateFlags().contains(NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_KEY_EXCH))) {
                        byte[] masterKey = new byte[16];
                        NtlmFunctions.RANDOM.nextBytes(masterKey);

                        byte[] exchangedKey = new byte[0];
                        try {
                            exchangedKey = NtlmFunctions.encryptRc4(userSessionKey, masterKey);
                        } catch (BadPaddingException | IllegalBlockSizeException e) {
                            throw new NtlmException("Expception while RC4 encryption", e);
                        }
                        sessionkey = exchangedKey;
                    } else {
                        sessionkey = userSessionKey;
                    }
                }

                SMB2SessionSetup smb2SessionSetup2 = new SMB2SessionSetup(connection.getNegotiatedDialect(), signingEnabled);
                smb2SessionSetup2.getHeader().setSessionId(sessionId);
                //smb2SessionSetup2.getHeader().setCreditRequest(256);

                NtlmChallengeResponse resp = new NtlmChallengeResponse(new byte[0], ntlmv2Response, sessionkey,
                        context.getUsername(), context.getDomain(), null);
                asn1 = negTokenTarg(resp, negTokenTarg.getResponseToken());
                smb2SessionSetup2.setSecurityBuffer(asn1);
                connection.send(smb2SessionSetup2);
                SMB2SessionSetup setupResponse = (SMB2SessionSetup) connection.receive();
                if (setupResponse.getHeader().getStatus() != SMB2StatusCode.STATUS_SUCCESS) {
                    throw new NtlmException("Setup failed with " + setupResponse.getHeader().getStatus());
                }
            }
            return sessionId;
        } catch (IOException | Buffer.BufferException e) {
            throw new TransportException(e);
        }
    }

    private byte[] negTokenInit(NtlmNegotiate ntlmNegotiate) {
        NegTokenInit negTokenInit = new NegTokenInit();
        negTokenInit.addSupportedMech(NTLMSSP);
        Buffer.PlainBuffer ntlmBuffer = new Buffer.PlainBuffer(Endian.LE);
        ntlmNegotiate.write(ntlmBuffer);
        negTokenInit.setMechToken(ntlmBuffer.getCompactData());
        Buffer.PlainBuffer negTokenBuffer = new Buffer.PlainBuffer(Endian.LE);
        negTokenInit.write(negTokenBuffer);
        return negTokenBuffer.getCompactData();
    }

    private byte[] negTokenTarg(NtlmChallengeResponse resp, byte[] responseToken) {
        NegTokenTarg targ = new NegTokenTarg();
        targ.setResponseToken(responseToken);
        Buffer.PlainBuffer ntlmBuffer = new Buffer.PlainBuffer(Endian.LE);
        resp.write(ntlmBuffer);
        targ.setResponseToken(ntlmBuffer.getCompactData());
        Buffer.PlainBuffer negTokenBuffer = new Buffer.PlainBuffer(Endian.LE);
        targ.write(negTokenBuffer);
        return negTokenBuffer.getCompactData();
    }

}
