/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package RSLBench.Algorithms.BMS;

import es.csic.iiia.maxsum.CommunicationAdapter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import rescuecore2.misc.Pair;

/**
 *
 * @author Marc Pujol <mpujol@iiia.csic.es>
 */
public class RSLBenchCommunicationAdapter implements CommunicationAdapter<NodeID> {
    private static final Logger Logger = LogManager.getLogger(RSLBenchCommunicationAdapter.class);

    /** Threshold below which messages are considered equal. */
    public static final double EPSILON = 1e-5;

    private ArrayList<BinaryMaxSumMessage> outgoingMessages;
    private Map<Pair<NodeID,NodeID>, Double> oldMessages;
    private boolean converged;

    public RSLBenchCommunicationAdapter() {
        outgoingMessages = new ArrayList<>();
        oldMessages = new HashMap<>();
        converged = true;
    }

    public Collection<BinaryMaxSumMessage> flushMessages() {
        Collection<BinaryMaxSumMessage> result = outgoingMessages;
        outgoingMessages = new ArrayList<>();
        converged = true;
        return result;
    }

    @Override
    public void send(double message, NodeID sender, NodeID recipient) {
        Logger.trace("Message from {} to {} : {}", new Object[]{sender, recipient, message});
        if (Double.isNaN(message) || Double.isInfinite(message)) {
            Logger.error("Factor {} tried to send {} to factor {}!", new Object[]{sender, message, recipient});
            System.exit(0);
        }

        // The algorithm has converged unless there is at least one message
        // different from the previous iteration
        Pair<NodeID, NodeID> sr = new Pair<>(sender, recipient);
        Double oldMessage = oldMessages.get(sr);
        if (oldMessage == null || isDifferent(oldMessage, message)) {
            converged = false;
        }
        oldMessages.put(sr, message);

        outgoingMessages.add(new BinaryMaxSumMessage(message, sender, recipient));
    }

    /**
     * Returns true if all the messages sent in the current iteration are
     * <em>equal</em> to the messages sent in the previous one.
     *
     * @see #EPSILON
     * @return true if the algorithm has converged, or false otherwise.
     */
    public boolean isConverged() {
        return converged;
    }

    private boolean isDifferent(double m1, double m2) {
        return Math.abs(m1 - m2) > EPSILON;
    }

}
