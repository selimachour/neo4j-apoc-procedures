package apoc.export.cypher.formatter;

import apoc.export.util.ExportConfig;
import apoc.export.util.ExportFormat;
import apoc.export.util.Reporter;
import apoc.util.Util;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.graphdb.*;
import org.neo4j.helpers.collection.Iterables;

import java.io.PrintWriter;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static apoc.export.cypher.formatter.CypherFormatterUtils.*;

/**
 * @author AgileLARUS
 *
 * @since 16-06-2017
 */
abstract class AbstractCypherFormatter implements CypherFormatter {

	private static final String STATEMENT_CONSTRAINTS = "CREATE CONSTRAINT ON (node:%s) ASSERT (%s) %s;";

	@Override
	public String statementForCleanUp(int batchSize) {
		return "MATCH (n:" + Q_UNIQUE_ID_LABEL + ") " +
				" WITH n LIMIT " + batchSize +
				" REMOVE n:" + Q_UNIQUE_ID_LABEL + " REMOVE n." + quote(UNIQUE_ID_PROP) + ";";
	}

	@Override
	public String statementForIndex(String label, Iterable<String> keys) {
		return "CREATE INDEX ON :" + Util.quote(label) + "(" + CypherFormatterUtils.quote(keys) + ");";
	}

	@Override
	public String statementForConstraint(String label, Iterable<String> keys) {

		String keysString = StreamSupport.stream(keys.spliterator(), false)
				.map(key -> "node." + CypherFormatterUtils.quote(key))
				.collect(Collectors.joining(", "));

		return  String.format(STATEMENT_CONSTRAINTS, Util.quote(label), keysString, Iterables.count(keys) > 1 ? "IS NODE KEY" : "IS UNIQUE");
	}

	protected String mergeStatementForNode(CypherFormat cypherFormat, Node node, Map<String, Set<String>> uniqueConstraints, Set<String> indexedProperties, Set<String> indexNames) {
		StringBuilder result = new StringBuilder(1000);
		result.append("MERGE ");
		result.append(CypherFormatterUtils.formatNodeLookup("n", node, uniqueConstraints, indexNames));
		if (node.getPropertyKeys().iterator().hasNext()) {
			String notUniqueProperties = CypherFormatterUtils.formatNotUniqueProperties("n", node, uniqueConstraints, indexedProperties, false);
			String notUniqueLabels = CypherFormatterUtils.formatNotUniqueLabels("n", node, uniqueConstraints);
			if (!"".equals(notUniqueProperties) || !"".equals(notUniqueLabels)) {
				result.append(cypherFormat.equals(CypherFormat.ADD_STRUCTURE) ? " ON CREATE SET " : " SET ");
				result.append(notUniqueProperties);
				result.append(!"".equals(notUniqueProperties) && !"".equals(notUniqueLabels) ? ", " : "");
				result.append(notUniqueLabels);
			}
		}
		result.append(";");
		return result.toString();
	}

	public String mergeStatementForRelationship(CypherFormat cypherFormat, Relationship relationship, Map<String, Set<String>> uniqueConstraints, Set<String> indexedProperties) {
		StringBuilder result = new StringBuilder(1000);
		result.append("MATCH ");
		result.append(CypherFormatterUtils.formatNodeLookup("n1", relationship.getStartNode(), uniqueConstraints, indexedProperties));
		result.append(", ");
		result.append(CypherFormatterUtils.formatNodeLookup("n2", relationship.getEndNode(), uniqueConstraints, indexedProperties));
		result.append(" MERGE (n1)-[r:" + CypherFormatterUtils.quote(relationship.getType().name()) + "]->(n2)");
		if (relationship.getPropertyKeys().iterator().hasNext()) {
			result.append(cypherFormat.equals(CypherFormat.UPDATE_STRUCTURE) ? " ON CREATE SET " : " SET ");
			result.append(CypherFormatterUtils.formatRelationshipProperties("r", relationship, false));
		}
		result.append(";");
		return result.toString();
	}

	public void buildStatementForNodes(String nodeClause, String setClause,
									   Iterable<Node> nodes, Map<String, Set<String>> uniqueConstraints,
									   ExportConfig exportConfig,
									   PrintWriter out, Reporter reporter,
									   GraphDatabaseService db) {
		AtomicInteger nodeCount = new AtomicInteger(0);
		Function<Node, Map.Entry<Set<String>, Set<String>>> keyMapper = (node) -> {
			try (Transaction tx = db.beginTx()) {
				Set<String> idProperties = CypherFormatterUtils.getNodeIdProperties(node, uniqueConstraints).keySet();
				Set<String> labels = getLabels(node);
				tx.success();
				return new AbstractMap.SimpleImmutableEntry<>(labels, idProperties);
			}
		};
		Map<Map.Entry<Set<String>, Set<String>>, List<Node>> groupedData = StreamSupport.stream(nodes.spliterator(), true)
				.collect(Collectors.groupingByConcurrent(keyMapper));

		AtomicInteger propertiesCount = new AtomicInteger(0);

		AtomicInteger batchCount = new AtomicInteger(0);
		groupedData.forEach((key, nodeList) -> {
			AtomicInteger unwindCount = new AtomicInteger(0);
			final int nodeListSize = nodeList.size();
			final Node last = nodeList.get(nodeListSize - 1);
			nodeCount.addAndGet(nodeListSize);
			for (int index = 0; index < nodeList.size(); index++) {
				Node node = nodeList.get(index);
				writeBatchBegin(exportConfig, out, batchCount);
				writeUnwindStart(exportConfig, out, unwindCount);
				batchCount.incrementAndGet();
				unwindCount.incrementAndGet();
				Map<String, Object> props = node.getAllProperties();
				// start element
				out.append("{");

				// id
				Map<String, Object> idMap = CypherFormatterUtils.getNodeIdProperties(node, uniqueConstraints);
				writeNodeIds(out, idMap);

				// properties
				out.append(", ");
				out.append("properties:");

				propertiesCount.addAndGet(props.size());
				props.keySet().removeAll(idMap.keySet());
				writeProperties(out, props);

				// end element
				out.append("}");
				if (last.equals(node) || isBatchMatch(exportConfig, batchCount) || isUnwindBatchMatch(exportConfig, unwindCount)) {
					closeUnwindNodes(nodeClause, setClause, uniqueConstraints, exportConfig, out, key, last);
					writeBatchEnd(exportConfig, out, batchCount);
					unwindCount.set(0);
				} else {
					out.append(", ");
				}
			}
		});
		addCommitToEnd(exportConfig, out, batchCount);

		reporter.update(nodeCount.get(), 0, propertiesCount.longValue());
	}

	private void closeUnwindNodes(String nodeClause, String setClause, Map<String, Set<String>> uniqueConstraints, ExportConfig exportConfig, PrintWriter out, Map.Entry<Set<String>, Set<String>> key, Node last) {
		writeUnwindEnd(exportConfig, out);
		out.append(StringUtils.LF);
		out.append(nodeClause);

		String label = getUniqueConstrainedLabel(last, uniqueConstraints);
		out.append("(n:");
		out.append(Util.quote(label));
		out.append("{");
		writeSetProperties(out, key.getValue());
		out.append("}) ");
		out.append(setClause);
		out.append("n += row.properties");
		String addLabels = key.getKey().stream()
				.filter(l -> !l.equals(label))
				.map(Util::quote)
				.collect(Collectors.joining(":"));
		if (!addLabels.isEmpty()) {
			out.append(" SET n:");
			out.append(addLabels);
		}
		out.append(";");
		out.append(StringUtils.LF);
	}

	private void writeSetProperties(PrintWriter out, Set<String> value) {
		writeSetProperties(out, value, null);
	}

	private void writeSetProperties(PrintWriter out, Set<String> value, String prefix) {
		if (prefix == null) prefix = "";
		int size = value.size();
		for (String s: value) {
			--size;
			out.append(Util.quote(s) + ": row." + prefix + formatNodeId(s));
			if (size > 0) {
				out.append(", ");
			}
		}
	}

	private boolean isBatchMatch(ExportConfig exportConfig, AtomicInteger batchCount) {
		return batchCount.get() % exportConfig.getBatchSize() == 0;
	}

	public void buildStatementForRelationships(String relationshipClause,
											   String setClause, Iterable<Relationship> relationship,
											   Map<String, Set<String>> uniqueConstraints, ExportConfig exportConfig,
											   PrintWriter out, Reporter reporter,
											   GraphDatabaseService db) {
		AtomicInteger relCount = new AtomicInteger(0);

		Function<Relationship, Map<String, Object>> keyMapper = (rel) -> {
			try (Transaction tx = db.beginTx()) {
				Node start = rel.getStartNode();
				Set<String> startLabels = getLabels(start);

				// define the end labels
				Node end = rel.getEndNode();
				Set<String> endLabels = getLabels(end);

				// define the type
				String type = rel.getType().name();

				// create the path
				Map<String, Object> key = Util.map("type", type,
						"start", new AbstractMap.SimpleImmutableEntry<>(startLabels, CypherFormatterUtils.getNodeIdProperties(start, uniqueConstraints).keySet()),
						"end", new AbstractMap.SimpleImmutableEntry<>(endLabels, CypherFormatterUtils.getNodeIdProperties(end, uniqueConstraints).keySet()));

				tx.success();
				return key;
			}
		};
		Map<Map<String, Object>, List<Relationship>> groupedData = StreamSupport.stream(relationship.spliterator(), true)
				.collect(Collectors.groupingByConcurrent(keyMapper));

		AtomicInteger propertiesCount = new AtomicInteger(0);
		AtomicInteger batchCount = new AtomicInteger(0);

		String start = "start";
		String end = "end";
		groupedData.forEach((path, relationshipList) -> {
			AtomicInteger unwindCount = new AtomicInteger(0);
			final int relSize = relationshipList.size();
			relCount.addAndGet(relSize);
			final Relationship last = relationshipList.get(relSize - 1);
			for (int index = 0; index < relationshipList.size(); index++) {
				Relationship rel = relationshipList.get(index);
				writeBatchBegin(exportConfig, out, batchCount);
				writeUnwindStart(exportConfig, out, unwindCount);
				batchCount.incrementAndGet();
				unwindCount.incrementAndGet();
				Map<String, Object> props = rel.getAllProperties();
				// start element
				out.append("{");

				// start node
				Node startNode = rel.getStartNode();
				writeRelationshipNodeIds(uniqueConstraints, out, start, startNode);

				out.append(", ");

				// end node
				Node endNode = rel.getEndNode();
				writeRelationshipNodeIds(uniqueConstraints, out, end, endNode);

				// properties
				out.append(", ");
				out.append("properties:");
				writeProperties(out, props);
				propertiesCount.addAndGet(props.size());

				// end element
				out.append("}");

				if (last.equals(rel) || isBatchMatch(exportConfig, batchCount) || isUnwindBatchMatch(exportConfig, unwindCount)) {
					closeUnwindRelationships(relationshipClause, setClause, uniqueConstraints, exportConfig, out, start, end, path, last);
					writeBatchEnd(exportConfig, out, batchCount);
					unwindCount.set(0);
				} else {
					out.append(", ");
				}
			}
		});
		addCommitToEnd(exportConfig, out, batchCount);

		reporter.update(0, relCount.get(), propertiesCount.longValue());
	}

	private void closeUnwindRelationships(String relationshipClause, String setClause, Map<String, Set<String>> uniqueConstraints, ExportConfig exportConfig, PrintWriter out, String start, String end, Map<String, Object> path, Relationship last) {
		writeUnwindEnd(exportConfig, out);
		// match start node
		writeRelationshipMatchAsciiNode(last.getStartNode(), out, start, path, uniqueConstraints);

		// match end node
		writeRelationshipMatchAsciiNode(last.getEndNode(), out, end, path, uniqueConstraints);

		out.append(StringUtils.LF);

		// create the relationship (depends on the strategy)
		out.append(relationshipClause);
		out.append("(start)-[r:" + Util.quote(path.get("type").toString()) + "]->(end) ");
		out.append(setClause);
		out.append("r += row.properties;");
		out.append(StringUtils.LF);
	}

	private boolean isUnwindBatchMatch(ExportConfig exportConfig, AtomicInteger batchCount) {
		return batchCount.get() % exportConfig.getUnwindBatchSize() == 0;
	}

	private void writeBatchEnd(ExportConfig exportConfig, PrintWriter out, AtomicInteger batchCount) {
		if (isBatchMatch(exportConfig, batchCount)) {
			out.append(exportConfig.getFormat().commit());
		}
	}

	public void writeProperties(PrintWriter out, Map<String, Object> props) {
		out.append("{");
		if (!props.isEmpty()) {
			int size = props.size();
			for (Map.Entry<String, Object> es : props.entrySet()) {
				--size;
				out.append(Util.quote(es.getKey()));
				out.append(":");
				out.append(CypherFormatterUtils.toString(es.getValue()));
				if (size > 0) {
					out.append(", ");
				}
			}
		}
		out.append("}");
	}

	private String formatNodeId(String key) {
		if (CypherFormatterUtils.UNIQUE_ID_PROP.equals(key)) {
			key = "_id";
		}
		return Util.quote(key);
	}

	private void addCommitToEnd(ExportConfig exportConfig, PrintWriter out, AtomicInteger batchCount) {
		if (batchCount.get() % exportConfig.getBatchSize() != 0) {
			out.append(exportConfig.getFormat().commit());
		}
	}

	private void writeBatchBegin(ExportConfig exportConfig, PrintWriter out, AtomicInteger batchCount) {
		if (isBatchMatch(exportConfig, batchCount)) {
			out.append(exportConfig.getFormat().begin());
		}
	}

	private void writeUnwindStart(ExportConfig exportConfig, PrintWriter out, AtomicInteger batchCount) {
		if (isUnwindBatchMatch(exportConfig, batchCount)) {
			String start = (exportConfig.getFormat() == ExportFormat.CYPHER_SHELL
					&& exportConfig.getOptimizationType() == ExportConfig.OptimizationType.UNWIND_BATCH_PARAMS) ?
					":param rows => [" : "UNWIND [";
			out.append(start);
		}
	}

	private void writeUnwindEnd(ExportConfig exportConfig, PrintWriter out) {
		out.append("]");
		if (exportConfig.getFormat() == ExportFormat.CYPHER_SHELL
				&& exportConfig.getOptimizationType() == ExportConfig.OptimizationType.UNWIND_BATCH_PARAMS) {
			out.append(StringUtils.LF);
			out.append("UNWIND $rows");
		}
		out.append(" AS row");
	}

	private String getUniqueConstrainedLabel(Node node, Map<String, Set<String>> uniqueConstraints) {
		return uniqueConstraints.entrySet().stream()
				.filter(e -> node.hasLabel(Label.label(e.getKey())) && e.getValue().stream().anyMatch(k -> node.hasProperty(k)))
				.map(e -> e.getKey())
				.findFirst()
				.orElse(CypherFormatterUtils.UNIQUE_ID_LABEL);
	}

	private Set<String> getLabels(Node node) {
		Set<String> labels = StreamSupport.stream(node.getLabels().spliterator(), false)
				.map(Label::name)
				.collect(Collectors.toSet());
		if (labels.isEmpty()) {
			labels.add(CypherFormatterUtils.UNIQUE_ID_LABEL);
		}
		return labels;
	}

	private void writeRelationshipMatchAsciiNode(Node node, PrintWriter out, String key, Map<String, Object> path, Map<String, Set<String>> uniqueConstraints) {
		Map.Entry<Set<String>, Set<String>> entry = (Map.Entry<Set<String>, Set<String>>) path.get(key);
		out.append(StringUtils.LF);
		out.append("MATCH ");
		out.append("(");
		out.append(key);
		out.append(":");
		out.append(Util.quote(getUniqueConstrainedLabel(node, uniqueConstraints)));
		out.append("{");
		writeSetProperties(out, entry.getValue(), key + ".");
		out.append("})");
	}

	private void writeRelationshipNodeIds(Map<String, Set<String>> uniqueConstraints, PrintWriter out, String key, Node node) {
		out.append(key + ": ");
		Set<String> props = uniqueConstraints.get(getUniqueConstrainedLabel(node, uniqueConstraints));
		Map<String, Object> properties;
		if (props != null && !props.isEmpty()) {
			String[] propsArray = props.toArray(new String[props.size()]);
			properties = node.getProperties(propsArray);
		} else {
			properties = Util.map(UNIQUE_ID_PROP, node.getId());
		}
		out.append("{");
		writeNodeIds(out, properties);
		out.append("}");
	}

	private void writeNodeIds(PrintWriter out, Map<String, Object> properties) {
		int size = properties.size();
		for (Map.Entry<String, Object> es : properties.entrySet()) {
			--size;
			out.append(formatNodeId(es.getKey()));
			out.append(":");
			out.append(CypherFormatterUtils.toString(es.getValue()));
			if (size > 0) {
				out.append(", ");
			}
		}
	}
}

