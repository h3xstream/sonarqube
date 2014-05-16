/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.qualityprofile.index;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rule.Severity;
import org.sonar.check.Cardinality;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.qualityprofile.db.ActiveRuleDto;
import org.sonar.core.qualityprofile.db.ActiveRuleParamDto;
import org.sonar.core.qualityprofile.db.QualityProfileDao;
import org.sonar.core.qualityprofile.db.QualityProfileDto;
import org.sonar.core.rule.RuleDto;
import org.sonar.core.rule.RuleParamDto;
import org.sonar.server.qualityprofile.ActiveRule;
import org.sonar.server.qualityprofile.persistence.ActiveRuleDao;
import org.sonar.server.rule2.persistence.RuleDao;
import org.sonar.server.tester.ServerTester;

import java.util.Collection;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class ActiveRuleIndexMediumTest {

  @ClassRule
  public static ServerTester tester = new ServerTester();

  MyBatis myBatis = tester.get(MyBatis.class);
  QualityProfileDao qualityProfileDao = tester.get(QualityProfileDao.class);
  ActiveRuleDao activeRuleDao = tester.get(ActiveRuleDao.class);
  RuleDao dao = tester.get(RuleDao.class);
  ActiveRuleIndex index = tester.get(ActiveRuleIndex.class);
  DbSession dbSession;

  @Before
  public void before() {
    tester.clearDataStores();
    dbSession = myBatis.openSession(false);
  }

  @After
  public void after() {
    dbSession.close();
  }

  @Test
  public void insert_and_index_activeRules() throws InterruptedException {
    QualityProfileDto profileDto = new QualityProfileDto()
      .setName("myprofile")
      .setLanguage("java");
    qualityProfileDao.insert(profileDto, dbSession);

    // insert db
    RuleKey ruleKey = RuleKey.of("javascript", "S001");
    RuleDto ruleDto = newRuleDto(ruleKey);
    dao.insert(ruleDto, dbSession);

    ActiveRuleDto activeRule = ActiveRuleDto.createFor(profileDto, ruleDto)
      .setInheritance(ActiveRule.Inheritance.INHERIT.name())
      .setSeverity(Severity.BLOCKER);

    activeRuleDao.insert(activeRule, dbSession);
    dbSession.commit();

    // verify that activeRules are persisted in db
    List<ActiveRuleDto> persistedDtos = activeRuleDao.findByRule(ruleDto, dbSession);
    assertThat(persistedDtos).hasSize(1);

    // verify that activeRules are indexed in es
    index.refresh();


    ActiveRule hit = index.getByKey(activeRule.getKey());

    assertThat(hit).isNotNull();
    assertThat(hit.key()).isEqualTo(activeRule.getKey());
    assertThat(hit.inheritance().name()).isEqualTo(activeRule.getInheritance());
    assertThat(hit.parentKey()).isEqualTo(activeRule.getParentId());
    assertThat(hit.severity()).isEqualTo(activeRule.getSeverityString());
  }

  @Test
  public void insert_and_index_activeRuleParams() throws InterruptedException {
    QualityProfileDto profileDto = new QualityProfileDto()
      .setName("myprofile")
      .setLanguage("java");
    qualityProfileDao.insert(profileDto, dbSession);

    // insert db
    RuleKey ruleKey = RuleKey.of("javascript", "S001");
    RuleDto ruleDto = newRuleDto(ruleKey);
    dao.insert(ruleDto, dbSession);

    RuleParamDto minParam = new RuleParamDto()
      .setName("min")
      .setType("STRING");
    dao.addRuleParam(ruleDto, minParam, dbSession);

    RuleParamDto maxParam = new RuleParamDto()
      .setName("max")
      .setType("STRING");
    dao.addRuleParam(ruleDto, maxParam, dbSession);


    ActiveRuleDto activeRule = ActiveRuleDto.createFor(profileDto, ruleDto)
      .setInheritance(ActiveRule.Inheritance.INHERIT.name())
      .setSeverity(Severity.BLOCKER);
    activeRuleDao.insert(activeRule, dbSession);

    ActiveRuleParamDto activeRuleMinParam = ActiveRuleParamDto.createFor(minParam)
      .setValue("minimum");
    activeRuleDao.addParam(activeRule, activeRuleMinParam, dbSession);

    ActiveRuleParamDto activeRuleMaxParam = ActiveRuleParamDto.createFor(maxParam)
      .setValue("maximum");
    activeRuleDao.addParam(activeRule, activeRuleMaxParam, dbSession);

    dbSession.commit();

    // verify that activeRulesParams are persisted in db
    List<ActiveRuleParamDto> persistedDtos = activeRuleDao.findParamsByActiveRule(activeRule, dbSession);
    assertThat(persistedDtos).hasSize(2);

    // verify that activeRulesParams are indexed in es
    index.refresh();

    ActiveRule rule = index.getByKey(activeRule.getKey());
    assertThat(rule.params()).hasSize(2);
    assertThat(rule.params().keySet()).containsOnly("min","max");
    assertThat(rule.params().values()).containsOnly("minimum","maximum");
    assertThat(rule.params().get("min")).isEqualTo("minimum");
  }

  @Test
  public void find_activeRules_by_ruleKey() throws InterruptedException {
    QualityProfileDto profileDto = new QualityProfileDto()
      .setName("myprofile")
      .setLanguage("java");

    QualityProfileDto profileDto2 = new QualityProfileDto()
      .setName("other-profile")
      .setLanguage("java");
    qualityProfileDao.insert(profileDto, dbSession);
    qualityProfileDao.insert(profileDto2, dbSession);

    // insert db
    RuleDto ruleDto = newRuleDto(RuleKey.of("javascript", "S001"));
    dao.insert(ruleDto, dbSession);

    // insert db
    RuleDto ruleDto2 = newRuleDto(RuleKey.of("javascript", "S002"));
    dao.insert(ruleDto2, dbSession);

    ActiveRuleDto find1 = ActiveRuleDto.createFor(profileDto, ruleDto)
      .setInheritance(ActiveRule.Inheritance.INHERIT.name())
      .setSeverity(Severity.BLOCKER);

    ActiveRuleDto find2 = ActiveRuleDto.createFor(profileDto2, ruleDto)
      .setInheritance(ActiveRule.Inheritance.INHERIT.name())
      .setSeverity(Severity.BLOCKER);

    ActiveRuleDto notFound = ActiveRuleDto.createFor(profileDto2, ruleDto2)
      .setInheritance(ActiveRule.Inheritance.INHERIT.name())
      .setSeverity(Severity.BLOCKER);

    activeRuleDao.insert(find1, dbSession);
    activeRuleDao.insert(find2, dbSession);
    activeRuleDao.insert(notFound, dbSession);
    dbSession.commit();

    // verify that activeRules are persisted in db
    List<ActiveRuleDto> persistedDtos = activeRuleDao.findByRule(ruleDto, dbSession);
    assertThat(persistedDtos).hasSize(2);
    persistedDtos = activeRuleDao.findByRule(ruleDto2, dbSession);
    assertThat(persistedDtos).hasSize(1);

    // verify that activeRules are indexed in es
    index.refresh();

    Collection<ActiveRule> hits = index.findByRule(RuleKey.of("javascript", "S001"));

    assertThat(hits).isNotNull();
    assertThat(hits).hasSize(2);

  }

  //TODO test delete, update, tags, params


  private RuleDto newRuleDto(RuleKey ruleKey) {
    return new RuleDto()
      .setRuleKey(ruleKey.rule())
      .setRepositoryKey(ruleKey.repository())
      .setName("Rule " + ruleKey.rule())
      .setDescription("Description " + ruleKey.rule())
      .setStatus(RuleStatus.READY.toString())
      .setConfigKey("InternalKey" + ruleKey.rule())
      .setSeverity(Severity.INFO)
      .setCardinality(Cardinality.SINGLE)
      .setLanguage("js")
      .setRemediationFunction("linear")
      .setDefaultRemediationFunction("linear_offset")
      .setRemediationCoefficient("1h")
      .setDefaultRemediationCoefficient("5d")
      .setRemediationOffset("5min")
      .setDefaultRemediationOffset("10h")
      .setEffortToFixDescription(ruleKey.repository() + "." + ruleKey.rule() + ".effortToFix");
  }
}
