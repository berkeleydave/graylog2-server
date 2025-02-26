/**
 * The MIT License
 * Copyright (c) 2012 Graylog, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.plugin.database.users;

import org.graylog2.plugin.database.Persisted;
import org.joda.time.DateTimeZone;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface User extends Persisted {
    boolean isReadOnly();

    String getFullName();

    String getName();

    void setName(String username);

    String getEmail();

    List<String> getPermissions();

    Map<String, Object> getPreferences();

    Map<String, String> getStartpage();

    long getSessionTimeoutMs();

    void setSessionTimeoutMs(long timeoutValue);

    void setPermissions(List<String> permissions);

    void setPreferences(Map<String, Object> preferences);

    void setEmail(String email);

    void setFullName(String fullname);

    String getHashedPassword();

    void setPassword(String password, String passwordSecret);

    boolean isUserPassword(String password, String passwordSecret);

    DateTimeZone getTimeZone();

    void setTimeZone(DateTimeZone timeZone);

    void setTimeZone(String timeZone);

    boolean isExternalUser();

    void setExternal(boolean external);

    void setStartpage(String type, String id);

    boolean isLocalAdmin();

    @Nonnull
    Set<String> getRoleIds();

    void setRoleIds(Set<String> roles);
}
