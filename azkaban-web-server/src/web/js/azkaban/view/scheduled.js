/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

$.namespace('azkaban');

var slaView;
var tableSorterView;
$(function () {
  slaView = new azkaban.ChangeSlaView({el: $('#sla-options')});
  tableSorterView = new azkaban.TableSorter({el: $('#scheduledFlowsTbl')});
});

function advFilter(evt) {
  var projectName = $('#searchboxproject').val();
  var flowName = $('#searchboxflow').val();

  var projectExactName = $('#projectExactName').val();
  var flowExactName = $('#flowExactName').val();

  var pageSize = $('#selector-page-size').val();

  console.log("event type: " + evt.type + ", page size: " + pageSize)

  if (projectName == "" && flowName == "" && projectExactName == "" && flowExactName == "") {
    redirectUrl = contextURL + "/schedule"
    if (pageSize != "") {
      redirectUrl = redirectUrl + "?size=" + pageSize;
    }
    window.location = redirectUrl
    return;
  }

  console.log("filtering scheduled, project: " + projectName + ", flow: " + flowName);
  console.log("filtering scheduled, exact project: " + projectExactName + ", exact flow: " + flowExactName);

  var redirectURL = contextURL + "/schedule";

  redirectURL = redirectURL + "?projectExactName=" + projectExactName + "&flowExactName=" + flowExactName + "&projectName=" + projectName + "&flowName=" + flowName;
  redirectURL = redirectURL + "&size=" + pageSize;

  window.location = redirectURL;
}

azkaban.ScheduleFilterView = Backbone.View.extend({
  events: {
    "click #quick-filter-btn": "handleAdvFilter",
  },

  handleAdvFilter: advFilter,

  render: function () {
  }
});

azkaban.AdvFilterView = Backbone.View.extend({
  events: {
    "click #filter-btn-exact": "handleAdvFilter",
  },

  handleAdvFilter: advFilter,

  initialize: function (settings) {
    $('#adv-filter-error-msg').hide();
  },

  render: function () {
  }
});


azkaban.SelectorView = Backbone.View.extend({
  events: {
    "change #selector-page-size": "pageSizeChanged",
  },

  pageSizeChanged: advFilter,

  render: function () {
  }
});

$(function () {
  filterView = new azkaban.ScheduleFilterView({el: $('#schedule-filter')});
  advFilterView = new azkaban.AdvFilterView({el: $('#adv-filter')});
  selectorView = new azkaban.SelectorView({el: $('#page-size-div')});
  $('#adv-filter-btn').click(function () {
    $('#adv-filter').modal();
  });
});
