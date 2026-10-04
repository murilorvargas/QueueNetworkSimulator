package QueueNetworkSimulator;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RandomGenerator {

    private static final Long A = 110351L;
    private static final Long C = 3233L;
    private static final Long M = 4194304L;

    private Long previous;
    private Integer count;

    public RandomGenerator() {
        this.previous = 93L;
        this.count = 0;
    }

    public Double nextRandom(Double min, Double max) {
        this.previous = ((A * this.previous) + C) % M;
        Double random = this.previous.doubleValue() / M;
        Double uniform = min + ((max - min) * random);
        this.count++;
        return uniform;
    }

    public Integer getCount() {
        return this.count;
    }
}

enum EventType {
    ARRIVAL,
    DEPARTURE
}

record Event(Queue queue, Queue destinationQueue, EventType eventType, Double time) {}

class Scheduler {

    private List<Event> events;

    public Scheduler() {
        this.events = new ArrayList<Event>();
    }

    public void scheduleEvent(Event event) {
        for (Integer i = 0; i < this.events.size(); i++) {
            if (event.time() < this.events.get(i).time()) {
                this.events.add(i, event);
                return;
            }
        }

        this.events.add(event);
    }

    public Event pollNextEvent() {
        return this.events.removeFirst();
    }
}

class Queue {

    private String name;
    private Integer capacity;
    private Integer servers;
    private Double minArrivalTime;
    private Double maxArrivalTime;
    private Double minServiceTime;
    private Double maxServiceTime;

    private Integer size;
    private List<Double> timePerSize;
    private Integer losses;

    private RandomGenerator randomGenerator;
    private Scheduler scheduler;
    private List<Route> routes;

    public Queue(
        String name,
        Integer capacity,
        Integer servers,
        Double minArrivalTime,
        Double maxArrivalTime,
        Double minServiceTime,
        Double maxServiceTime,
        RandomGenerator randomGenerator,
        Scheduler scheduler
    ) {
        this.name = name;
        this.capacity = capacity;
        this.servers = servers;
        this.minArrivalTime = minArrivalTime;
        this.maxArrivalTime = maxArrivalTime;
        this.minServiceTime = minServiceTime;
        this.maxServiceTime = maxServiceTime;

        this.size = 0;
        this.losses = 0;

        Integer initialStates = this.capacity.equals(Integer.MAX_VALUE) ? 1 : this.capacity + 1;
        this.timePerSize = new ArrayList<Double>();
        for (Integer i = 0; i < initialStates; i++) {
            this.timePerSize.add(0.0);
        }

        this.randomGenerator = randomGenerator;
        this.scheduler = scheduler;
        this.routes = new ArrayList<Route>();
    }

    private Queue selectNextQueue() {
        if (this.routes.isEmpty()) return null;
        if (this.routes.size() == 1 && this.routes.getFirst().probability() >= 1.0) return this.routes.getFirst().destination();

        Double probabilitySum = 0.0;
        Double probability = this.randomGenerator.nextRandom(0.0, 1.0);
        for (Route route : this.routes) {
            probabilitySum += route.probability();
            if (probability < probabilitySum) return route.destination();
        }

        return null;
    }

    public void addRoute(Route route) {
        for (Integer i = 0; i < this.routes.size(); i++) {
            if (route.probability() < this.routes.get(i).probability()) {
                this.routes.add(i, route);
                return;
            }
        }

        this.routes.add(route);
    }

    public void updateTimePerSize(Double newTime, Double previousTime) {
        Double elapsedTime = newTime - previousTime;
        this.timePerSize.set(this.size, Double.sum(this.timePerSize.get(this.size), elapsedTime));
    }

    public void receiveArrival(Event event) {
        if (this.size < this.capacity) {
            this.size++;
            if (this.size >= this.timePerSize.size()) {
                this.timePerSize.add(0.0);
            }

            if (this.size <= this.servers) {
                Queue destinationQueue = this.selectNextQueue();
                Event newEvent = new Event(this, destinationQueue, EventType.DEPARTURE, Double.sum(event.time(), randomGenerator.nextRandom(this.minServiceTime, this.maxServiceTime)));
                this.scheduler.scheduleEvent(newEvent);
            }

            return;
        }

        this.losses++;
    }

    public void processArrival(Event event, Double previousTime) {
        if (event.eventType() != EventType.ARRIVAL) throw new IllegalArgumentException("Evento informado não é do tipo ARRIVAL");

        this.receiveArrival(event);

        Event newEvent = new Event(this, null, EventType.ARRIVAL, Double.sum(event.time(), randomGenerator.nextRandom(this.minArrivalTime, this.maxArrivalTime)));
        this.scheduler.scheduleEvent(newEvent);
    }

    public void processDeparture(Event event, Double previousTime) {
        if (event.eventType() != EventType.DEPARTURE) throw new IllegalArgumentException("Evento informado não é do tipo DEPARTURE");

        this.size--;

        if (this.size >= this.servers) {
            Queue destinationQueue = this.selectNextQueue();
            Event newEvent = new Event(this, destinationQueue, EventType.DEPARTURE, Double.sum(event.time(), randomGenerator.nextRandom(this.minServiceTime, this.maxServiceTime)));
            this.scheduler.scheduleEvent(newEvent);
        }

        if (event.destinationQueue() != null) {
            event.destinationQueue().receiveArrival(event);
        }
    }

    public void printResults(Double globalTimer) {
        System.out.println("===== " + this.name + " =====");
        System.out.printf("Tempo global de simulação: %.4f%n", globalTimer);
        System.out.printf("Servidores: %d | Capacidade: %s%n", this.servers, this.capacity.equals(Integer.MAX_VALUE) ? "infinito" : this.capacity);

        System.out.println("Estado | Tempo acumulado | Probabilidade");
        for (Integer i = 0; i < this.timePerSize.size(); i++) {
            Double accumulatedTime = this.timePerSize.get(i);
            Double probability = accumulatedTime / globalTimer;
            System.out.printf("%6d | %16.4f | %12.4f%n", i, accumulatedTime, probability);
        }

        System.out.println();
        if (this.losses > 0) {
            System.out.printf("Clientes perdidos: %d%n", this.losses);
        } else {
            System.out.println("Não houve perda de clientes.");
        }
        System.out.println();
    }
}

record Route(Queue destination, Double probability) {}

record SimulationModel(List<Queue> queues, List<Event> firstArrivals, Integer iterations) {}

class SimulationModelLoader {

    private Path path;

    private String section;
    private Map<String, String> currentEntry;

    private Map<String, String> parameters;
    private Map<String, String> arrivals;
    private Map<String, Map<String, String>> queues;
    private List<Map<String, String>> network;

    public SimulationModelLoader(Path path) {
        this.path = path;

        this.section = "";
        this.currentEntry = null;

        this.parameters = new LinkedHashMap<>();
        this.arrivals = new LinkedHashMap<>();
        this.queues = new LinkedHashMap<>();
        this.network = new ArrayList<>();
    }

    private void readLine(String line) {
        String text = line.strip();
        if (text.isEmpty() || text.startsWith("#") || text.startsWith("!")) return;

        String[] pair = text.replaceFirst("^-", "").split(":", 2);
        String key = pair[0].strip();
        String value = pair[1].strip();

        if (!line.startsWith(" ") && !text.startsWith("-")) {
            if (value.isEmpty()) {
                this.section = key;
            } else {
                this.section = "";
                this.parameters.put(key, value);
            }
            return;
        }

        if (this.section.equals("arrivals")) {
            this.arrivals.put(key, value);
            return;
        }

        if (this.section.equals("queues") && value.isEmpty()) {
            this.currentEntry = new LinkedHashMap<>();
            this.queues.put(key, this.currentEntry);
            return;
        }

        if (this.section.equals("network") && text.startsWith("-")) {
            this.currentEntry = new LinkedHashMap<>();
            this.network.add(this.currentEntry);
        }

        this.currentEntry.put(key, value);
    }

    private static Double getDouble(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null) return null;

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor inválido na propriedade: " + key);
        }
    }

    private static Integer getInteger(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null) return null;

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor inválido na propriedade: " + key);
        }
    }

    private Queue createQueue(String name, Map<String, String> properties, RandomGenerator randomGenerator, Scheduler scheduler) {
        Integer capacity = getInteger(properties, "capacity");
        Integer servers = getInteger(properties, "servers");
        Double minArrival = getDouble(properties, "minArrival");
        Double maxArrival = getDouble(properties, "maxArrival");
        Double minService = getDouble(properties, "minService");
        Double maxService = getDouble(properties, "maxService");

        if (servers == null || minService == null || maxService == null) throw new IllegalArgumentException("Propriedades obrigatórias não informadas na fila: " + name);
        if (servers < 1) throw new IllegalArgumentException("Quantidade de servidores inválida na fila: " + name);
        if (capacity != null && capacity < 1) throw new IllegalArgumentException("Capacidade inválida na fila: " + name);
        if (minService < 0 || minService > maxService) throw new IllegalArgumentException("Tempo de serviço inválido na fila: " + name);

        Boolean receivesArrivals = this.arrivals.containsKey(name);
        if (receivesArrivals && (minArrival == null || maxArrival == null)) throw new IllegalArgumentException("Propriedades de chegada não informadas na fila: " + name);
        if (!receivesArrivals && (minArrival != null || maxArrival != null)) throw new IllegalArgumentException("Propriedades de chegada informadas em fila fora de arrivals: " + name);
        if (receivesArrivals && (minArrival < 0 || minArrival > maxArrival)) throw new IllegalArgumentException("Tempo de chegada inválido na fila: " + name);

        return new Queue(
            name,
            capacity != null ? capacity : Integer.MAX_VALUE,
            servers,
            minArrival,
            maxArrival,
            minService,
            maxService,
            randomGenerator,
            scheduler
        );
    }

    private void validateProbabilities() {
        Map<String, Double> probabilitySums = new LinkedHashMap<>();
        for (Map<String, String> route : this.network) {
            String source = route.get("source");
            Double probability = getDouble(route, "probability");
            if (probability < 0) throw new IllegalArgumentException("Probabilidade negativa na fila: " + source);

            Double probabilitySum = probabilitySums.getOrDefault(source, 0.0) + probability;
            if (probabilitySum > 1.0) throw new IllegalArgumentException("Soma das probabilidades maior que 1 na fila: " + source);
            probabilitySums.put(source, probabilitySum);
        }
    }

    public SimulationModel load(RandomGenerator randomGenerator, Scheduler scheduler) throws IOException {
        for (String line : Files.readAllLines(this.path)) {
            this.readLine(line);
        }

        Integer iterations = getInteger(this.parameters, "rndnumbersLimit");

        Map<String, Queue> queuesByName = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : this.queues.entrySet()) {
            String name = entry.getKey();
            queuesByName.put(name, this.createQueue(name, entry.getValue(), randomGenerator, scheduler));
        }

        this.validateProbabilities();
        for (Map<String, String> route : this.network) {
            Queue source = queuesByName.get(route.get("source"));
            Queue target = queuesByName.get(route.get("target"));
            source.addRoute(new Route(target, getDouble(route, "probability")));
        }

        List<Event> firstArrivals = new ArrayList<>();
        for (String name : this.arrivals.keySet()) {
            Double time = getDouble(this.arrivals, name);
            if (time < 0) throw new IllegalArgumentException("Tempo de chegada negativo na fila: " + name);

            firstArrivals.add(new Event(queuesByName.get(name), null, EventType.ARRIVAL, time));
        }

        return new SimulationModel(new ArrayList<>(queuesByName.values()), firstArrivals, iterations);
    }
}

class QueueNetworkSimulator {

    private Double globalTimer;

    private SimulationModelLoader simulationModelLoader;
    private List<Queue> queues;
    private RandomGenerator randomGenerator;
    private Scheduler scheduler;

    public QueueNetworkSimulator(Path modelPath) {
        this.globalTimer = 0.0;

        this.simulationModelLoader = new SimulationModelLoader(modelPath);
        this.queues = new ArrayList<Queue>();
        this.randomGenerator = new RandomGenerator();
        this.scheduler = new Scheduler();
    }

    private void updateQueuesTimePerSize(Event event) {
        for (Queue queue : this.queues) {
            queue.updateTimePerSize(event.time(), this.globalTimer);
        }
    }

    private void printResults() {
        System.out.println("===== Resultado da simulação =====");
        System.out.printf("Aleatórios utilizados: %d%n", this.randomGenerator.getCount());
        System.out.printf("Tempo global de simulação: %.4f%n%n", this.globalTimer);

        for (Queue queue : this.queues) {
            queue.printResults(this.globalTimer);
        }
    }

    public void run() throws IOException {
        SimulationModel model = this.simulationModelLoader.load(this.randomGenerator, this.scheduler);
        this.queues.addAll(model.queues());
        for (Event event : model.firstArrivals()) {
            this.scheduler.scheduleEvent(event);
        }

        while (this.randomGenerator.getCount() < model.iterations()) {
            Event event = this.scheduler.pollNextEvent();
            this.updateQueuesTimePerSize(event);

            if (event.eventType() == EventType.ARRIVAL) {
                event.queue().processArrival(event, this.globalTimer);
            } else if (event.eventType() == EventType.DEPARTURE) {
                event.queue().processDeparture(event, this.globalTimer);
            } else {
                throw new IllegalArgumentException("Tipo de evento desconhecido: " + event.eventType());
            }

            this.globalTimer = event.time();
        }

        printResults();
    }
}

public class QueueNetworkSimulatorApp {

    private static final String MODEL_FILE_NAME = "model.yml";

    private static Path findModelPath() throws URISyntaxException {
        Path location = Path.of(QueueNetworkSimulatorApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(location)) {
            location = location.resolve(QueueNetworkSimulatorApp.class.getPackageName().replace('.', '/'));
        } else {
            location = location.getParent();
        }
        return location.resolve(MODEL_FILE_NAME);
    }

    public static void main(String[] args) {
        try {
            Path modelPath = findModelPath();
            System.out.println("Modelo: " + modelPath.toAbsolutePath().normalize());
            System.out.println();
            new QueueNetworkSimulator(modelPath).run();
        } catch (IllegalArgumentException e) {
            System.err.println("Modelo inválido: " + e.getMessage());
        } catch (IOException | URISyntaxException e) {
            System.err.println("Erro ao ler o modelo: " + e.getMessage());
        }
    }
}
