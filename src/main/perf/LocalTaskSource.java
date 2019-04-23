package perf;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.BytesRef;

// Serves up tasks from locally loaded list:
class LocalTaskSource implements TaskSource {
  private final List<Task> tasks;
  private final AtomicInteger nextTask = new AtomicInteger();

  public LocalTaskSource(IndexState indexState, TaskParser taskParser, String tasksFile,
                         Random staticRandom, Random random, int numTaskPerCat, int taskRepeatCount, boolean doPKLookup) throws IOException, ParseException {

    final List<Task> loadedTasks = loadTasks(taskParser, tasksFile);
    Collections.shuffle(loadedTasks, staticRandom);
    final List<Task> prunedTasks = pruneTasks(loadedTasks, numTaskPerCat);

    final IndexSearcher searcher = indexState.mgr.acquire();
    final int maxDoc;
    try {
      maxDoc = searcher.getIndexReader().maxDoc();
    } finally {
      indexState.mgr.release(searcher);
    }

    // Add PK tasks
    //System.out.println("WARNING: skip PK tasks");
    if (doPKLookup) {
      final int numPKTasks = (int) Math.min(maxDoc/6000., numTaskPerCat);
      final Set<BytesRef> pkSeenIDs = new HashSet<BytesRef>();
      final Set<Integer> pkSeenIntIDs = new HashSet<Integer>();
      for(int idx=0;idx<numPKTasks;idx++) {
        prunedTasks.add(new PKLookupTask(maxDoc, staticRandom, 4000, pkSeenIDs, idx));
        //prunedTasks.add(new PointsPKLookupTask(maxDoc, staticRandom, 4000, pkSeenIntIDs, idx));
      }
      /*
      final Set<BytesRef> pkSeenSingleIDs = new HashSet<BytesRef>();
      for(int idx=0;idx<numPKTasks*100;idx++) {
        prunedTasks.add(new SinglePKLookupTask(maxDoc, staticRandom, pkSeenSingleIDs, idx));
      }
      */
    }

    tasks = new ArrayList<Task>();

    // Copy the pruned tasks multiple times, shuffling the order each time:
    for(int iter=0;iter<taskRepeatCount;iter++) {
      Collections.shuffle(prunedTasks, random);
      for(Task task : prunedTasks) {
        tasks.add(task.clone());
      }
    }
    System.out.println("TASK LEN=" + tasks.size());
  }

  @Override
  public List<Task> getAllTasks() {
    return tasks;
  }

  private static List<Task> pruneTasks(List<Task> tasks, int numTaskPerCat) {
    final Map<String,Integer> catCounts = new HashMap<String,Integer>();
    final List<Task> newTasks = new ArrayList<Task>();
    for(Task task : tasks) {
      final String cat = task.getCategory();
      Integer v = catCounts.get(cat);
      int catCount;
      if (v == null) {
        catCount = 0;
      } else {
        catCount = v.intValue();
      }

      if (catCount >= numTaskPerCat) {
        // System.out.println("skip task cat=" + cat);
        continue;
      }
      catCount++;
      catCounts.put(cat, catCount);
      newTasks.add(task);
    }

    return newTasks;
  }

  @Override
  public Task nextTask() {
    final int next = nextTask.getAndIncrement();
    if (next >= tasks.size()) {
      return null;
    }
    return tasks.get(next);
  }

  @Override
  public void taskDone(Task task, long queueTimeNS, TotalHits toalHitCount) {
  }

  static List<Task> loadTasks(TaskParser taskParser, String filePath) throws IOException, ParseException {
    final List<Task> tasks = new ArrayList<Task>();
    final BufferedReader taskFile = new BufferedReader(new InputStreamReader(new FileInputStream(filePath), "UTF-8"), 16384);
    List<SearchTask> intNRQ = new ArrayList<>();
    List<SearchTask> highTerms = new ArrayList<>();
    List<SearchTask> medTerms = new ArrayList<>();
    List<SearchTask> lowTerms = new ArrayList<>();
    List<SearchTask> prefixTasks = new ArrayList<>();
    List<SearchTask> wildcardTasks = new ArrayList<>();
    while (true) {
      String line = taskFile.readLine();
      if (line == null) {
        break;
      }
      line = line.trim();
      if (line.indexOf("#") == 0) {
        // Ignore comment lines
        continue;
      }
      if (line.length() == 0) {
        // Ignore blank lines
        continue;
      }

      Task task = taskParser.parseOneTask(line);
      tasks.add(task);

      if (task instanceof SearchTask) {
        SearchTask searchTask = (SearchTask) task;
        if (searchTask.getCategory().equals("IntNRQ")) {
          intNRQ.add(searchTask);
        }
        if (searchTask.getCategory().equals("LowTerm")) {
          lowTerms.add(searchTask);
        }
        if (searchTask.getCategory().equals("MedTerm")) {
          medTerms.add(searchTask);
        }
        if (searchTask.getCategory().equals("HighTerm")) {
          highTerms.add(searchTask);
        }
        if (searchTask.getCategory().equals("Prefix3")) {
          prefixTasks.add(searchTask);
        }
        if (searchTask.getCategory().equals("Wildcard")) {
          wildcardTasks.add(searchTask);
        }
      }
    }

    combineTasks(intNRQ, highTerms, medTerms, lowTerms, tasks, taskParser, "IntNRQ");

    combineTasks(prefixTasks, highTerms, medTerms, lowTerms, tasks, taskParser, "Prefix");

    combineTasks(wildcardTasks, highTerms, medTerms, lowTerms, tasks, taskParser, "Wildcard");

    taskFile.close();
    return tasks;
  }

  private static void combineTasks(List<SearchTask> baseTasks, List<SearchTask> highTerms,
                                   List<SearchTask> medTerms, List<SearchTask> lowTerms,
                                   List<Task> tasks, TaskParser taskParser, String prefix) {
    for (SearchTask prefixTask : baseTasks) {
      for (SearchTask highTerm : highTerms) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(prefixTask.q, BooleanClause.Occur.MUST);
        builder.add(highTerm.q, BooleanClause.Occur.MUST);
        tasks.add(new SearchTask(prefix + "ConjHighTerm", builder.build(), prefixTask.s, null, taskParser.topN, false,
                false, Collections.<String>emptyList(), false));
      }
      for (SearchTask medTerm : medTerms) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(prefixTask.q, BooleanClause.Occur.MUST);
        builder.add(medTerm.q, BooleanClause.Occur.MUST);
        tasks.add(new SearchTask(prefix + "ConjMedTerm", builder.build(), prefixTask.s, null, taskParser.topN, false,
                false, Collections.<String>emptyList(), false));
      }
      for (SearchTask lowTerm : lowTerms) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(prefixTask.q, BooleanClause.Occur.MUST);
        builder.add(lowTerm.q, BooleanClause.Occur.MUST);
        tasks.add(new SearchTask(prefix + "ConjLowTerm", builder.build(), prefixTask.s, null, taskParser.topN, false,
                false, Collections.<String>emptyList(), false));
      }
    }

  }
}
