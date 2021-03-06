/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.ddl.workloadmanagement.trigger.create;

import java.io.Serializable;

import org.apache.hadoop.hive.ql.ddl.DDLDesc;
import org.apache.hadoop.hive.ql.plan.Explain;
import org.apache.hadoop.hive.ql.plan.Explain.Level;

/**
 * DDL task description for CREATE TRIGGER commands.
 */
@Explain(displayName="Create WM Trigger", explainLevels={ Level.USER, Level.DEFAULT, Level.EXTENDED })
public class CreateWMTriggerDesc implements DDLDesc, Serializable {
  private static final long serialVersionUID = 1L;

  private final String resourcePlanName;
  private final String triggerName;
  private final String triggerExpression;
  private final String actionExpression;

  public CreateWMTriggerDesc(String resourcePlanName, String triggerName, String triggerExpression,
      String actionExpression) {
    this.resourcePlanName = resourcePlanName;
    this.triggerName = triggerName;
    this.triggerExpression = triggerExpression;
    this.actionExpression = actionExpression;
  }

  @Explain(displayName="Resource plan name", explainLevels={ Level.USER, Level.DEFAULT, Level.EXTENDED })
  public String getResourcePlanName() {
    return resourcePlanName;
  }

  @Explain(displayName="Trigger name", explainLevels={ Level.USER, Level.DEFAULT, Level.EXTENDED })
  public String getTriggerName() {
    return triggerName;
  }

  @Explain(displayName="Trigger expression", explainLevels={ Level.USER, Level.DEFAULT, Level.EXTENDED })
  public String getTriggerExpression() {
    return triggerExpression;
  }

  @Explain(displayName="Action expression", explainLevels={ Level.USER, Level.DEFAULT, Level.EXTENDED })
  public String getActionExpression() {
    return actionExpression;
  }
}
