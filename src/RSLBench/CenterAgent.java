package RSLBench;


import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import rescuecore2.messages.Command;
import rescuecore2.standard.components.StandardAgent;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;

import RSLBench.Assignment.Assignment;
import RSLBench.Assignment.CompositeSolver;
import RSLBench.Assignment.Solver;
import RSLBench.Helpers.Exporter;
import RSLBench.Helpers.Logging.Markers;
import RSLBench.Helpers.Utility.UtilityFactory;
import RSLBench.Helpers.Utility.UtilityMatrix;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import rescuecore2.worldmodel.WorldModel;
import rescuecore2.worldmodel.WorldModelListener;

/**
 * It is a "fake" agent that does not appears in the graphic simulation, but that serves as a "station"
 * for all the other agent. It is the agent that starts and updates the simulation and that
 * communicates the new target to each PlatoonFireAgent.
 */
public class CenterAgent extends StandardAgent<Building>
{
    private static final Logger Logger = LogManager.getLogger(CenterAgent.class);

    /** Base config key to solver configurations */
    public static final String CONF_KEY_SOLVER= "solver";

    /** Config key of a solver class to run */
    public static final String CONF_KEY_CLASS = "class";

    /** Config key to the maximum time allowed for the main oslver */
    public static final String CONF_KEY_TIME = "time";

    private Solver solver = null;
    private Exporter exporter = null;
    private ArrayList<EntityID> agents = new ArrayList<>();
    private Assignment lastAssignment = new Assignment();
    private List<PlatoonFireAgent> fireAgents;

    public CenterAgent(List<PlatoonFireAgent> fireAgents) {
    	Logger.info(Markers.BLUE, "Center Agent CREATED");
        this.fireAgents = fireAgents;
        for (PlatoonFireAgent fagent : fireAgents) {
            agents.add(fagent.getID());
        }
    }

    @Override
    public String toString()
    {
        return "Center Agent";
    }

    /**
     * Sets up the center agent.
     *
     * At this point, the center agent already has a world model, and has
     * laoded the kernel's configuration. Hence, it is ready to setup the
     * assignment solver(s).
     */
    @Override
    public void postConnect() {
        super.postConnect();

        Logger.warn("Checking for blockades...");
        for (StandardEntity e : model.getAllEntities()) {
            if (e instanceof Blockade) {
                Logger.warn("Initial blockade detected: " + e);
            }
        }

        model.addWorldModelListener(new WorldModelListener<StandardEntity>() {
            @Override
            public void entityAdded(WorldModel<? extends StandardEntity> model,
                    StandardEntity e) {
                if (e instanceof Blockade) {
                    Logger.warn("New blockade introduced: " + e);
                }
            }

            @Override
            public void entityRemoved(WorldModel<? extends StandardEntity> model,
                    StandardEntity e) {
                if (e instanceof Blockade) {
                    Logger.warn("Blockade removed: " + e);
                }
            }
        });

        initializeParameters();

        if (config.getBooleanValue(Constants.KEY_EXPORT)) {
            exporter = new Exporter();
            exporter.initialize(model, config);
        }

        solver = buildSolver();
        solver.initialize(model, config);
    }

    private void initializeParameters() {
        // Set a UUID for this run
        if (!config.isDefined(Constants.KEY_RUN_ID)) {
            Logger.warn("Setting run id to generated value.");
            config.setValue(Constants.KEY_RUN_ID, UUID.randomUUID().toString());
        }

        // Set the utility function to use
        String utilityClass = config.getValue(Constants.KEY_UTILITY_CLASS);
        UtilityFactory.setClass(utilityClass);

        // Extract the map and scenario names
        String map = config.getValue("gis.map.dir");
        map = map.substring(map.lastIndexOf("/")+1);
        config.setValue(Constants.KEY_MAP_NAME, map);
        String scenario = config.getValue("gis.map.scenario");
        scenario = scenario.substring(scenario.lastIndexOf("/")+1);
        config.setValue(Constants.KEY_MAP_SCENARIO, scenario);

        // The experiment can not start before the agent ignore time
        int ignore = config.getIntValue(kernel.KernelConstants.IGNORE_AGENT_COMMANDS_KEY);
        int start  = config.getIntValue(Constants.KEY_START_EXPERIMENT_TIME);
        if (ignore > start) {
            Logger.error("The experiment can't start at time {} because agent commands are ignored until time {}", start, ignore);
            System.exit(0);
        }
    }

    private Solver buildSolver() {
        // Load main solver class
        solver = buildSolver(
                config.getValue(CONF_KEY_SOLVER + "." + CONF_KEY_CLASS),
                config.getIntValue(CONF_KEY_SOLVER + "." + CONF_KEY_TIME));
        Logger.info("Using main solver: {}", solver.getIdentifier());
        config.setValue(Constants.KEY_MAIN_SOLVER, solver.getIdentifier());

        // And any additional test solvers
        CompositeSolver comp = null;
        for(int nTestClass=1;;nTestClass++) {
            String key = CONF_KEY_SOLVER + "." + nTestClass + "." + CONF_KEY_CLASS;
            String className = config.getValue(key, null);
            if (className == null) {
                break;
            }

            if (comp == null) {
                comp = new CompositeSolver(solver);
                solver = comp;
            }

            Solver s = buildSolver(className, config.getIntValue(
                    CONF_KEY_SOLVER + "." + nTestClass + "." + CONF_KEY_TIME));
            Logger.info("Also testing solver: {}", s.getIdentifier());
            comp.addSolver(s);
        }

        return solver;
    }

    private Solver buildSolver(String clazz, int time) {
        try {
            Class<?> c = Class.forName(clazz);
            Object s = c.newInstance();
            if (s instanceof Solver) {
                Solver newSolver = (Solver)s;
                newSolver.setMaxTime(time);
                return newSolver;
            }

        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            Logger.catching(Level.ERROR, ex);
        }

        Logger.error("Unable to initialize solver {}", clazz);
        System.exit(1);
        return null;
    }

    @Override
    protected void think(int time, ChangeSet changed, Collection<Command> heard)
    {
        Collection<EntityID> burning = getBurningBuildings();
        Logger.info(Markers.WHITE, "TIME IS {} | {} known burning buildings.",
                new Object[]{time, burning.size()});

        if (time < config.getIntValue(Constants.KEY_START_EXPERIMENT_TIME)) {
            Logger.debug("Waiting until experiment starts.");
            return;
        }

        // Stop the simulation if all fires have been extinguished
        if (burning.isEmpty()) {
            Logger.info("All fires extinguished. Good job!");
            System.exit(0);
        }

        // Build the problem
        ArrayList<EntityID> targets = new ArrayList<>(burning);
        UtilityMatrix utility = new UtilityMatrix(config, agents, targets, lastAssignment, model);

        // Export the problem if required
        if (exporter != null) {
            exporter.export(utility);
        }

        // Compute assignment
        lastAssignment = solver.solve(time, utility);

        // Send assignment to agents
        for (PlatoonFireAgent fagent : fireAgents) {
            if (lastAssignment != null) {
                fagent.enqueueAssignment(lastAssignment.getAssignment(fagent.getID()));
            } else {
                fagent.enqueueAssignment(Assignment.UNKNOWN_TARGET_ID);
            }
        }
    }

    @Override
    protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum()
    {
        return EnumSet.of(StandardEntityURN.FIRE_STATION, StandardEntityURN.AMBULANCE_CENTRE, StandardEntityURN.POLICE_OFFICE);
    }

    /**
     * It returns the burning buildings
     * @return a collection of burning buildings.
     */
    private Collection<EntityID> getBurningBuildings()
    {
        Collection<StandardEntity> e = model.getEntitiesOfType(StandardEntityURN.BUILDING);
        List<EntityID> result = new ArrayList<>();
        for (StandardEntity next : e)
        {
            if (next instanceof Building)
            {
                Building b = (Building) next;
                if (b.getFieryness() > 0 && b.getFieryness() < 4)
                {
                    EntityID id = b.getID();
                    if (id == null) {
                        Logger.warn("Found a building with no id: {}. Dropped.", b);
                    }
                    result.add(id);
                }
            }
        }
        // Sort by distance
        return result;
    }
}
