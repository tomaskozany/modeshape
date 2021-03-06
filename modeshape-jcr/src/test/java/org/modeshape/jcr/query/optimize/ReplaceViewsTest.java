/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jcr.query.optimize;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.modeshape.jcr.ExecutionContext;
import org.modeshape.jcr.NodeTypes;
import org.modeshape.jcr.RepositoryIndexes;
import org.modeshape.jcr.api.query.qom.Operator;
import org.modeshape.jcr.cache.RepositoryCache;
import org.modeshape.jcr.query.AbstractQueryTest;
import org.modeshape.jcr.query.BufferManager;
import org.modeshape.jcr.query.QueryContext;
import org.modeshape.jcr.query.model.Column;
import org.modeshape.jcr.query.model.Comparison;
import org.modeshape.jcr.query.model.Literal;
import org.modeshape.jcr.query.model.PropertyValue;
import org.modeshape.jcr.query.model.SelectorName;
import org.modeshape.jcr.query.plan.PlanNode;
import org.modeshape.jcr.query.plan.PlanNode.Property;
import org.modeshape.jcr.query.plan.PlanNode.Type;
import org.modeshape.jcr.query.validate.ImmutableSchemata;
import org.modeshape.jcr.query.validate.Schemata;

/**
 * 
 */
public class ReplaceViewsTest extends AbstractQueryTest {

    private ReplaceViews rule;
    private QueryContext context;
    private Schemata schemata;
    private ImmutableSchemata.Builder builder;

    @Before
    public void beforeEach() {
        ExecutionContext executionContext = new ExecutionContext();
        rule = ReplaceViews.INSTANCE;
        builder = ImmutableSchemata.createBuilder(executionContext, mock(NodeTypes.class));
        builder.addTable("t1", "c11", "c12", "c13");
        builder.addTable("t2", "c21", "c22", "c23");
        builder.addView("v1", "SELECT c11, c12 FROM t1 WHERE c13 < CAST('3' AS LONG)");
        builder.addView("v2", "SELECT t1.c11, t1.c12, t2.c23 FROM t1 JOIN t2 ON t1.c11 = t2.c21");
        schemata = builder.build();
        context = new QueryContext(new ExecutionContext(), mock(RepositoryCache.class), Collections.singleton("workspace"),
                                   mock(Schemata.class), mock(RepositoryIndexes.class), mock(NodeTypes.class),
                                   mock(BufferManager.class));
    }

    /**
     * Before:
     * 
     * <pre>
     *          ...
     *           |
     *        PROJECT      with the list of columns being SELECTed
     *           |
     *        SELECT1
     *           |         One or more SELECT plan nodes that each have
     *        SELECT2      a single non-join constraint that are then all AND-ed
     *           |         together
     *        SELECT3
     *           |
     *        SOURCE       A SOURCE that uses the view
     * </pre>
     * 
     * And after:
     * 
     * <pre>
     *          ...
     *           |
     *        PROJECT      with the list of columns being SELECTed
     *           |
     *        SELECT1
     *           |         One or more SELECT plan nodes that each have
     *        SELECT2      a single non-join constraint that are then all AND-ed
     *           |         together
     *        SELECT3
     *           |
     *        SOURCE
     *           |
     *        PROJECT      with the list of columns in the SELECT of the view
     *           |
     *        SELECT1      One or more SELECT plan nodes that each have a single non-join constraint
     *           |         that are then all AND-ed together (as defined by the view plan
     *           |
     *        SOURCE       A SOURCE that the view uses
     * </pre>
     */
    @Test
    public void shouldReplaceViewWithPlanForViewWithSingleTable() {
        // Each of the PROJECT, SELECT, and SELECT nodes must have the names of the selectors that they apply to ...
        PlanNode project = new PlanNode(Type.PROJECT, selector("v1"));
        PlanNode select1 = new PlanNode(Type.SELECT, project, selector("v1"));
        PlanNode select2 = new PlanNode(Type.SELECT, select1, selector("v1"));
        PlanNode select3 = new PlanNode(Type.SELECT, select2, selector("v1"));
        PlanNode source = new PlanNode(Type.SOURCE, select3, selector("v1"));
        source.setProperty(Property.SOURCE_NAME, selector("v1"));

        // Create the equivalent plan nodes for what should be created ...
        PlanNode viewProject = new PlanNode(Type.PROJECT, selector("t1"));
        PlanNode viewSelect = new PlanNode(Type.SELECT, viewProject, selector("t1"));
        PlanNode viewSource = new PlanNode(Type.SOURCE, viewSelect, selector("t1"));
        viewProject.setProperty(Property.PROJECT_COLUMNS, columns(column("t1", "c11"), column("t1", "c12")));
        viewSelect.setProperty(Property.SELECT_CRITERIA, new Comparison(new PropertyValue(selector("t1"), "c13"),
                                                                        Operator.LESS_THAN, new Literal(3L)));
        viewSource.setProperty(Property.SOURCE_NAME, selector("t1"));
        viewSource.setProperty(Property.SOURCE_COLUMNS, schemata.getTable(selector("t1")).getColumns());

        // Execute the rule ...
        PlanNode result = rule.execute(context, project, new LinkedList<OptimizerRule>());
        // System.out.println(project);
        // System.out.println(result);
        assertThat(result.isSameAs(project), is(true));
        assertChildren(project, select1);
        assertChildren(select1, select2);
        assertChildren(select2, select3);
    }

    protected List<Column> columns( Column... columns ) {
        return Arrays.asList(columns);
    }

    protected Column column( String table,
                             String columnName ) {
        return new Column(new SelectorName(table), columnName, columnName);
    }

    protected Column column( String table,
                             String columnName,
                             String alias ) {
        return new Column(new SelectorName(table), columnName, alias);
    }
}
