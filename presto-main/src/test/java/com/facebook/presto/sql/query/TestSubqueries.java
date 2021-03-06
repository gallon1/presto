/*
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
package com.facebook.presto.sql.query;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestSubqueries
{
    private static final String UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG = "line .*: Given correlated subquery is not supported";

    private QueryAssertions assertions;

    @BeforeClass
    public void init()
    {
        assertions = new QueryAssertions();
    }

    @AfterClass(alwaysRun = true)
    public void teardown()
    {
        assertions.close();
    }

    @Test
    public void testCorrelatedExistsSubqueriesWithOrPredicateAndNull()
    {
        assertions.assertQuery(
                "SELECT EXISTS(SELECT 1 FROM (VALUES null, 10) t(x) WHERE y > x OR y + 10 > x) FROM (values (11)) t2(y)",
                "VALUES true");
        assertions.assertQuery(
                "SELECT EXISTS(SELECT 1 FROM (VALUES null) t(x) WHERE y > x OR y + 10 > x) FROM (values (11)) t2(y)",
                "VALUES false");
    }

    @Test
    public void testUnsupportedSubqueriesWithCoercions()
    {
        // coercion from subquery symbol type to correlation type
        assertions.assertFails(
                "select (select count(*) from (values 1) t(a) where t.a=t2.b limit 1) from (values 1.0) t2(b)",
                UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG);
        // coercion from t.a (null) to integer
        assertions.assertFails(
                "select EXISTS(select 1 from (values (null, null)) t(a, b) where t.a=t2.b GROUP BY t.b) from (values 1, 2) t2(b)",
                UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG);
    }

    @Test
    public void testCorrelatedSubqueriesWithLimit()
    {
        assertions.assertQuery(
                "select (select t.a from (values 1, 2) t(a) where t.a=t2.b limit 1) from (values 1) t2(b)",
                "VALUES 1");
        // cannot enforce limit 2 on correlated subquery
        assertions.assertFails(
                "select (select t.a from (values 1, 2) t(a) where t.a=t2.b limit 2) from (values 1) t2(b)",
                UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG);
        assertions.assertQuery(
                "select (select sum(t.a) from (values 1, 2) t(a) where t.a=t2.b group by t.a limit 2) from (values 1) t2(b)",
                "VALUES BIGINT '1'");
        assertions.assertQuery(
                "select (select count(*) from (select t.a from (values 1, 1, null, 3) t(a) limit 1) t where t.a=t2.b) from (values 1, 2) t2(b)",
                "VALUES BIGINT '1', BIGINT '0'");
        assertions.assertQuery(
                "select EXISTS(select 1 from (values 1, 1, 3) t(a) where t.a=t2.b limit 1) from (values 1, 2) t2(b)",
                "VALUES true, false");
        // TransformCorrelatedScalarAggregationToJoin does not fire since limit is above aggregation node
        assertions.assertFails(
                "select (select count(*) from (values 1, 1, 3) t(a) where t.a=t2.b limit 1) from (values 1) t2(b)",
                UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG);
        assertions.assertQuery(
                "SELECT t.cid, t.rid " +
                        "FROM (values (1, 101)) t(cid, rid) " +
                        "WHERE EXISTS(SELECT 1 FROM (values ('x', 1, 101)) u(x, cid, rid) WHERE x = 'x' AND t.cid = cid AND t.rid = rid LIMIT 1)",
                "VALUES (1, 101)");
    }

    @Test
    public void testCorrelatedSubqueriesWithGroupBy()
    {
        // t.a is not a "constant" column, group by does not guarantee single row per correlated subquery
        assertions.assertFails(
                "select (select count(*) from (values 1, 2, 3, null) t(a) where t.a<t2.b GROUP BY t.a) from (values 1, 2, 3) t2(b)",
                UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG);
        assertions.assertQuery(
                "select EXISTS(select 1 from (values 1, 1, 3) t(a) where t.a=t2.b GROUP BY t.a) from (values 1, 2) t2(b)",
                "VALUES true, false");
        assertions.assertQuery(
                "select EXISTS(select 1 from (values (1, 2), (1, 2), (null, null), (3, 3)) t(a, b) where t.a=t2.b GROUP BY t.a, t.b) from (values 1, 2) t2(b)",
                "VALUES true, false");
        assertions.assertQuery(
                "select EXISTS(select 1 from (values (1, 2), (1, 2), (null, null), (3, 3)) t(a, b) where t.a<t2.b GROUP BY t.a, t.b) from (values 1, 2) t2(b)",
                "VALUES false, true");
        // t.b is not a "constant" column, cannot be pushed above aggregation
        assertions.assertFails(
                "select EXISTS(select 1 from (values (1, 1), (1, 1), (null, null), (3, 3)) t(a, b) where t.a+t.b<t2.b GROUP BY t.a) from (values 1, 2) t2(b)",
                UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG);
        assertions.assertQuery(
                "select EXISTS(select 1 from (values (1, 1), (1, 1), (null, null), (3, 3)) t(a, b) where t.a+t.b<t2.b GROUP BY t.a, t.b) from (values 1, 4) t2(b)",
                "VALUES false, true");
        assertions.assertQuery(
                "select EXISTS(select 1 from (values (1, 2), (1, 2), (null, null), (3, 3)) t(a, b) where t.a=t2.b GROUP BY t.b) from (values 1, 2) t2(b)",
                "VALUES true, false");
        assertions.assertQuery(
                "select EXISTS(select * from (values 1, 1, 2, 3) t(a) where t.a=t2.b GROUP BY t.a HAVING count(*) > 1) from (values 1, 2) t2(b)",
                "VALUES true, false");
        assertions.assertQuery(
                "select EXISTS(select * from (select t.a from (values (1, 1), (1, 1), (1, 2), (1, 2), (3, 3)) t(a, b) where t.b=t2.b GROUP BY t.a HAVING count(*) > 1) t where t.a=t2.b)" +
                        " from (values 1, 2) t2(b)",
                "VALUES true, false");
        assertions.assertQuery(
                "select EXISTS(select * from (values 1, 1, 2, 3) t(a) where t.a=t2.b GROUP BY (t.a) HAVING count(*) > 1) from (values 1, 2) t2(b)",
                "VALUES true, false");
    }

    @Test
    public void testCorrelatedLateralWithGroupBy()
    {
        assertions.assertQuery(
                "select * from (values 1, 2) t2(b), LATERAL (select t.a from (values 1, 1, 3) t(a) where t.a=t2.b GROUP BY t.a)",
                "VALUES (1, 1)");
        assertions.assertQuery(
                "select * from (values 1, 2) t2(b), LATERAL (select count(*) from (values 1, 1, 2, 3) t(a) where t.a=t2.b GROUP BY t.a HAVING count(*) > 1)",
                "VALUES (1, BIGINT '2')");
        // correlated subqueries with grouping sets are not supported
        assertions.assertFails(
                "select * from (values 1, 2) t2(b), LATERAL (select t.a, t.b, count(*) from (values (1, 1), (1, 2), (2, 2), (3, 3)) t(a, b) where t.a=t2.b GROUP BY GROUPING SETS ((t.a, t.b), (t.a)))",
                UNSUPPORTED_CORRELATED_SUBQUERY_ERROR_MSG);
    }
}
