package com.owera.xaps.base.http;

import java.io.IOException;
import java.sql.SQLException;

import javax.servlet.http.HttpServletResponse;

import com.owera.common.db.NoAvailableConnectionException;
import com.owera.common.log.Context;
import com.owera.xaps.base.BaseCache;
import com.owera.xaps.base.Log;
import com.owera.xaps.base.NoDataAvailableException;
import com.owera.xaps.dbi.util.SystemParameters;
import com.owera.xaps.tr069.HTTPReqResData;
import com.owera.xaps.tr069.Properties;
import com.owera.xaps.tr069.SessionData;
import com.owera.xaps.tr069.exception.TR069AuthenticationException;

public class BasicAuthenticator {

	private static void sendChallenge(HttpServletResponse res) {
		// Send challenge
		String authParam = "Basic realm=\"" + Util.getRealm() + "\"";
		res.addHeader("WWW-Authenticate", authParam);
		try {
			res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		} catch (IOException ioe) {
			Log.warn(DigestAuthenticator.class, "Unable to make challenge", ioe);
		}
	}

	public static boolean authenticate(HTTPReqResData reqRes) throws TR069AuthenticationException {

		String authorization = reqRes.getReq().getHeader("authorization");
		if (authorization == null) {
			Log.notice(BasicAuthenticator.class, "Send challenge to CPE, located on IP-address " + reqRes.getReq().getRemoteHost());
			sendChallenge(reqRes.getRes());
			return false;
		} else {
			return (verify(reqRes, authorization));
		}

	}

	/**
	 * Verifies login against database
	 * 
	 * @param request
	 *            HTTP servlet request
	 * @param authorization
	 *            Authorization credentials from this request
	 * @throws TR069AuthenticationException 
	 */
	private static boolean verify(HTTPReqResData reqRes, String authorization) throws TR069AuthenticationException {

		Log.debug(BasicAuthenticator.class, "Basic verification of CPE starts, located on IP-address " + reqRes.getReq().getRemoteHost());
		authorization = authorization.trim();
		authorization = Util.removePrefix(authorization, "basic");
		authorization = authorization.trim();
		String userpass = Util.base64decode(authorization);

		// Validate any credentials already included with this request
		String username = null;
		String password = null;

		// Get username and password
		int colon = userpass.indexOf(':');
		if (colon < 0) {
			username = userpass;
		} else {
			username = userpass.substring(0, colon);
			password = userpass.substring(colon + 1, userpass.length());
		}

		// Do database read parameters and then perform verification
		String unitId = Util.username2unitId(username);
		Context.put(Context.X, unitId, BaseCache.SESSIONDATA_CACHE_TIMEOUT);
		Log.debug(DigestAuthenticator.class, "Basic verification identifed unit id " + unitId + " from CPE IP-address " + reqRes.getReq().getRemoteHost());
		try {
			SessionData sessionData = reqRes.getSessionData();
			sessionData.setUnitId(unitId);
			sessionData.updateParametersFromDB(unitId); // Unit is now stored in sessionData
			String secret = null;
			if (sessionData.isFirstConnect() && Properties.isDiscoveryMode()) {
				for (String blocked : Properties.getDiscoveryBlocked()) {
					if (unitId.contains(blocked))
						throw new TR069AuthenticationException("ACS Username is blocked by \"" + blocked + "\" in discovery mode. Access denied", null, HttpServletResponse.SC_FORBIDDEN);
				}
				secret = password;
				sessionData.setSecret(secret);
				Log.warn(DigestAuthenticator.class, "Authentication not verified, but accepted since in Discovery Mode");
			}
			BaseCache.putSessionData(unitId, sessionData);
			//			if (secret == null)
			//				secret = sessionData.getOweraParameters().getValue(SystemParameters.SHARED_SECRET);
			if (secret == null) {
				secret = sessionData.getOweraParameters().getValue(SystemParameters.SECRET);
				if (secret != null && !secret.equals(password) && secret.length() > 16)
					secret = secret.substring(0, 16);
			}
			//			if (secret == null)
			//				secret = sessionData.getOweraParameters().getValue(SystemParameters.TR069_SECRET);
			if (secret == null) {
				throw new TR069AuthenticationException("No ACS Password found in database (CPE IP address: " + reqRes.getReq().getRemoteHost() + ") (username: " + username + ")", null,
						HttpServletResponse.SC_FORBIDDEN);
			} else if (!secret.equals(password)) {
				throw new TR069AuthenticationException("Incorrect ACS Password (CPE IP address: " + reqRes.getReq().getRemoteHost() + ") (username: " + username + ")", null,
						HttpServletResponse.SC_FORBIDDEN);
			} else {
				Log.notice(BasicAuthenticator.class, "Authentication verified (CPE IP address: " + reqRes.getReq().getRemoteHost() + ")");
				return true;
			}
		} catch (NoAvailableConnectionException e) {
			throw new TR069AuthenticationException("Authentication failed because of no available database connections  (CPE IP address: " + reqRes.getReq().getRemoteHost() + ") (username: "
					+ username + ")", e, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		} catch (SQLException e) {
			throw new TR069AuthenticationException("Authentication failed because of database error (CPE IP address: " + reqRes.getReq().getRemoteHost() + ") (username: " + username + ")", e,
					HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		} catch (NoDataAvailableException e) {
			throw new TR069AuthenticationException("Authentication failed because unitid was not found (CPE IP address: " + reqRes.getReq().getRemoteHost() + ") (username: " + username + ")", e,
					HttpServletResponse.SC_FORBIDDEN);
		}
	}
}
