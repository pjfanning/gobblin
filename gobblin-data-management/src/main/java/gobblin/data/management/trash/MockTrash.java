/*
 * Copyright (C) 2014-2015 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.data.management.trash;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.google.common.collect.Lists;


/**
 * Mock version of {@link gobblin.data.management.trash.ProxiedTrash} for simulating deletions. Can also be used as
 * a mock for {@link gobblin.data.management.trash.Trash}.
 */
public class MockTrash extends ProxiedTrash {

  public MockTrash(FileSystem fs, Properties props, String user)
      throws IOException {
    super(fs, props, user);
  }

  @Override
  public boolean moveToTrash(Path path)
      throws IOException {
    return true;
  }

  @Override
  public void createTrashSnapshot()
      throws IOException {
    throw new UnsupportedOperationException("Not supported for " + MockTrash.class);
  }

  @Override
  public void purgeTrashSnapshots()
      throws IOException {
    throw new UnsupportedOperationException("Not supported for " + MockTrash.class);
  }

  @Override
  protected Path createTrashLocation(FileSystem fs, Properties props, String user)
      throws IOException {
    return super.createTrashLocation(fs, props, user);
  }

  @Override
  protected List<String> getAllUsersWithTrash()
      throws IOException {
    return Lists.newArrayList();
  }

  @Override
  protected void ensureTrashLocationExists(FileSystem fs, Path trashLocation)
      throws IOException {
    // Do nothing
  }

  @Override
  protected Trash getUserTrash(String user)
      throws IOException {
    return this;
  }
}
