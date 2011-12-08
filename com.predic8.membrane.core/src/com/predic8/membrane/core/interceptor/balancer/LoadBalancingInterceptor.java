/* Copyright 2009 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */
package com.predic8.membrane.core.interceptor.balancer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.predic8.membrane.core.config.AbstractXmlElement;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.Message;
import com.predic8.membrane.core.http.Response;
import com.predic8.membrane.core.interceptor.AbstractInterceptor;
import com.predic8.membrane.core.interceptor.Outcome;

/**
 * May only be used as interceptor in a ServiceProxy.
 */
public class LoadBalancingInterceptor extends AbstractInterceptor {

	private static Log log = LogFactory.getLog(LoadBalancingInterceptor.class
			.getName());

	private DispatchingStrategy strategy = new RoundRobinStrategy();
	private AbstractSessionIdExtractor sessionIdExtractor;
	private boolean failOver = true;
	private final Balancer balancer = new Balancer();

	public LoadBalancingInterceptor() {
		name = "Balancer";
	}
	
	@Override
	public Outcome handleRequest(Exchange exc) throws Exception {
		log.debug("handleRequest");

		Node dispatchedNode;
		try {
			dispatchedNode = getDispatchedNode(exc.getRequest());
		} catch (EmptyNodeListException e) {
			log.error("No Node found.");
			exc.setResponse(Response.interalServerError().build());
			return Outcome.ABORT;
		}

		dispatchedNode.incCounter();
		dispatchedNode.addThread();

		exc.setProperty("dispatchedNode", dispatchedNode);

		exc.setOriginalRequestUri(getDestinationURL(dispatchedNode, exc));

		exc.getDestinations().clear();
		exc.getDestinations().add(getDestinationURL(dispatchedNode, exc));

		setFailOverNodes(exc, dispatchedNode);

		return Outcome.CONTINUE;
	}

	@Override
	public Outcome handleResponse(Exchange exc) throws Exception {
		log.debug("handleResponse");

		if (sessionIdExtractor != null) {
			String sessionId = getSessionId(exc.getResponse());

			if (sessionId != null) {
				balancer.addSession2Cluster(sessionId, "Default", (Node) exc.getProperty("dispatchedNode"));
			}
		}

		updateDispatchedNode(exc);

		return Outcome.CONTINUE;
	}

	private void setFailOverNodes(Exchange exc, Node dispatchedNode)
			throws MalformedURLException {
		if (!failOver)
			return;

		for (Node ep : getEndpoints()) {
			if (!ep.equals(dispatchedNode))
				exc.getDestinations().add(getDestinationURL(ep, exc));
		}
	}

	private void updateDispatchedNode(Exchange exc) {
		Node n = (Node) exc.getProperty("dispatchedNode");
		n.removeThread();
		// exc.timeReqSent will be overridden later as exc really
		// completes, but to collect the statistics we use the current time
		exc.setTimeReqSent(System.currentTimeMillis());
		n.collectStatisticsFrom(exc);
	}

	private Node getDispatchedNode(Message msg) throws Exception {
		String sessionId;
		if (sessionIdExtractor == null
				|| (sessionId = getSessionId(msg)) == null) {
			log.debug("no session id found.");
			return strategy.dispatch(this);
		}

		Session s = getSession(sessionId);
		if (s == null)
			log.debug("no session found for id " + sessionId);
		if (s == null || s.getNode().isDown()) {
			log.debug("assigning new node for session id " + sessionId
					+ (s != null ? " (old node was " + s.getNode() + ")" : ""));
			balancer.addSession2Cluster(sessionId, "Default", strategy.dispatch(this));
		}
		s = getSession(sessionId);
		s.used();
		return s.getNode();
	}

	private Session getSession(String sessionId) {
		return balancer.getSessions("Default").get(sessionId);
	}

	public String getDestinationURL(Node ep, Exchange exc)
			throws MalformedURLException {
		return "http://" + ep.getHost() + ":" + ep.getPort()
				+ getRequestURI(exc);
	}

	private String getSessionId(Message msg) throws Exception {
		return sessionIdExtractor.getSessionId(msg);
	}

	private String getRequestURI(Exchange exc) throws MalformedURLException {
		if (exc.getOriginalRequestUri().toLowerCase().startsWith("http://")) 
			// TODO what about HTTPS?
			return new URL(exc.getOriginalRequestUri()).getFile();

		return exc.getOriginalRequestUri();
	}

	/**
	 * This is *NOT* {@link #setDisplayName(String)}, but the balancer's name
	 * set in the proxy configuration to identify this balancer.
	 */
	public void setName(String name) throws Exception {
		balancer.setName(name);
	}
	
	
	/**
	 * This is *NOT* {@link #getDisplayName()}, but the balancer's name set in
	 * the proxy configuration to identify this balancer.
	 */
	public String getName() {
		return balancer.getName();
	}

	public DispatchingStrategy getDispatchingStrategy() {
		return strategy;
	}

	public void setDispatchingStrategy(DispatchingStrategy strategy) {
		this.strategy = strategy;
	}

	public List<Node> getEndpoints() {
		return balancer.getAvailableNodesByCluster("Default");
	}

	public AbstractSessionIdExtractor getSessionIdExtractor() {
		return sessionIdExtractor;
	}

	public void setSessionIdExtractor(
			AbstractSessionIdExtractor sessionIdExtractor) {
		this.sessionIdExtractor = sessionIdExtractor;
	}

	public boolean isFailOver() {
		return failOver;
	}

	public void setFailOver(boolean failOver) {
		this.failOver = failOver;
	}
	
	public Balancer getClusterManager() {
		return balancer;
	}

	public long getSessionTimeout() {
		return balancer.getSessionTimeout();
	}

	public void setSessionTimeout(long sessionTimeout) {
		balancer.setSessionTimeout(sessionTimeout);
	}

	@Override
	protected void writeInterceptor(XMLStreamWriter out)
			throws XMLStreamException {

		out.writeStartElement("balancer");

		out.writeAttribute("name", balancer.getName());
		if (getSessionTimeout() != SessionCleanupThread.DEFAULT_TIMEOUT)
			out.writeAttribute("sessionTimeout", ""+getSessionTimeout());

		if (sessionIdExtractor != null) {
			sessionIdExtractor.write(out);
		}

		balancer.write(out);

		((AbstractXmlElement) strategy).write(out);

		out.writeEndElement();
	}

	protected void parseAttributes(XMLStreamReader token) throws Exception {
		if (token.getAttributeValue("", "name") != null)
			setName(token.getAttributeValue("", "name"));
		else
			setName("Default");
		if (token.getAttributeValue("", "sessionTimeout") != null)
			setSessionTimeout(Integer.parseInt(token.getAttributeValue("", "sessionTimeout")));
	}

	
	@Override
	protected void parseChildren(XMLStreamReader token, String child)
			throws Exception {
		
		if (token.getLocalName().equals("xmlSessionIdExtractor")) {
			sessionIdExtractor = new XMLElementSessionIdExtractor();
			sessionIdExtractor.parse(token);
		} else if (token.getLocalName().equals("jSessionIdExtractor")) {
			sessionIdExtractor = new JSESSIONIDExtractor();
			sessionIdExtractor.parse(token);
		} else if (token.getLocalName().equals("clusters")) {
			balancer.parse(token);
		} else if (token.getLocalName().equals("byThreadStrategy")) {
			parseByThreadStrategy(token);
		} else if (token.getLocalName().equals("roundRobinStrategy")) {
			parseRoundRobinStrategy(token);
		} else {
			super.parseChildren(token, child);
		}
	}

	private void parseByThreadStrategy(XMLStreamReader token) throws Exception {
		ByThreadStrategy byTStrat = new ByThreadStrategy();
		byTStrat.parse(token);
		strategy = byTStrat;
	}

	private void parseRoundRobinStrategy(XMLStreamReader token)
			throws Exception {
		RoundRobinStrategy rrStrat = new RoundRobinStrategy();
		rrStrat.parse(token);
		strategy = rrStrat;
	}

}
