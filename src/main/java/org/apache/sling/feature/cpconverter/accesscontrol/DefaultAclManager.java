/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.cpconverter.accesscontrol;

import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.PrivilegeDefinition;
import org.apache.jackrabbit.spi.commons.conversion.DefaultNamePathResolver;
import org.apache.jackrabbit.spi.commons.conversion.NameResolver;
import org.apache.jackrabbit.vault.fs.spi.PrivilegeDefinitions;
import org.apache.jackrabbit.vault.util.PlatformNameFormat;
import org.apache.sling.feature.cpconverter.features.FeaturesManager;
import org.apache.sling.feature.cpconverter.shared.RepoPath;
import org.apache.sling.feature.cpconverter.vltpkg.VaultPackageAssembler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.jcr.NamespaceException;
import java.io.File;
import java.io.FileInputStream;
import java.util.Optional;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class DefaultAclManager implements AclManager {

    private static final String CONTENT_XML_FILE_NAME = ".content.xml";

    private static final String DEFAULT_TYPE = "sling:Folder";

    private final Set<RepoPath> preProvidedSystemPaths = new HashSet<>();

    private final Set<RepoPath> preProvidedPaths = new HashSet<>();

    private final Set<SystemUser> systemUsers = new LinkedHashSet<>();

    private final Map<String, List<AccessControlEntry>> acls = new HashMap<>();

    private final List<String> nodetypeRegistrationSentences = new LinkedList<>();

    private volatile PrivilegeDefinitions privilegeDefinitions;

    public boolean addSystemUser(@NotNull SystemUser systemUser) {
        return systemUsers.add(systemUser);
    }

    public boolean addAcl(@NotNull String systemUser, @NotNull AccessControlEntry acl) {
        if (getSystemUser(systemUser).isPresent()) {
            acls.computeIfAbsent(systemUser, k -> new LinkedList<>()).add(acl);
            return true;
        }
        return false;
    }

    private void addPath(@NotNull RepoPath path, @NotNull Set<RepoPath> paths) {
        if (preProvidedPaths.add(path)) {
            paths.add(path);
        }

        RepoPath parent = path.getParent();
        if (parent != null && parent.getSegmentCount() > 0) {
            addPath(parent, paths);
        }
    }

    public void addRepoinitExtension(@NotNull List<VaultPackageAssembler> packageAssemblers, @NotNull FeaturesManager featureManager) {
        try (Formatter formatter = new Formatter()) {

            if (privilegeDefinitions != null) {
                registerPrivileges(privilegeDefinitions, formatter);
            }

            for (String nodetypeRegistrationSentence : nodetypeRegistrationSentences) {
                formatter.format("%s%n", nodetypeRegistrationSentence);
            }

            // system users

            for (SystemUser systemUser : systemUsers) {
                // make sure all users are created first
                formatter.format("create service user %s with path %s%n", systemUser.getId(), systemUser.getIntermediatePath());
            }

            Set<RepoPath> paths = acls.entrySet().stream()
                    .filter(entry -> getSystemUser(entry.getKey()).isPresent())
                    .map(Entry::getValue)
                    .flatMap(Collection::stream)
                    .map(AccessControlEntry::getRepositoryPath).collect(Collectors.toSet());

            paths.stream()
                    .filter(path -> !paths.stream().anyMatch(other -> !other.equals(path) && other.startsWith(path)))
                    .filter(((Predicate<RepoPath>)RepoPath::isRepositoryPath).negate())
                    .map(path -> computePathWithTypes(path, packageAssemblers))
                    .filter(Objects::nonNull)
                    .forEach(
                            path -> formatter.format("create path %s%n", path)
                    );

            // add the acls
            acls.forEach((systemUserID, authorizations) ->
                getSystemUser(systemUserID).ifPresent(systemUser ->
                    addStatements(systemUser, authorizations, packageAssemblers, formatter)
                ));

            String text = formatter.toString();

            if (!text.isEmpty()) {
                featureManager.addOrAppendRepoInitExtension(text, null);
            }
        }
    }

    private void addStatements(@NotNull SystemUser systemUser,
                               @NotNull List<AccessControlEntry> authorizations,
                               @NotNull List<VaultPackageAssembler> packageAssemblers,
                               @NotNull Formatter formatter) {
        // clean the unneeded ACLs, see SLING-8561
        Iterator<AccessControlEntry> authorizationsIterator = authorizations.iterator();
        while (authorizationsIterator.hasNext()) {
            AccessControlEntry acl = authorizationsIterator.next();

            if (acl.getRepositoryPath().startsWith(systemUser.getIntermediatePath())) {
                authorizationsIterator.remove();
            }
        }
        // finally add ACLs

        addAclStatement(formatter, systemUser, authorizations);
    }

    private @NotNull Optional<SystemUser> getSystemUser(@NotNull String id) {
        return systemUsers.stream().filter(systemUser ->  systemUser.getId().equals(id)).findFirst();
    }

    @Override
    public void addNodetypeRegistrationSentence(@Nullable String nodetypeRegistrationSentence) {
        if (nodetypeRegistrationSentence != null) {
            nodetypeRegistrationSentences.add(nodetypeRegistrationSentence);
        }
    }

    @Override
    public void addPrivilegeDefinitions(@NotNull PrivilegeDefinitions privilegeDefinitions) {
        this.privilegeDefinitions = privilegeDefinitions;
    }

    public void reset() {
        systemUsers.clear();
        acls.clear();
        nodetypeRegistrationSentences.clear();
        privilegeDefinitions = null;
    }

    private static @Nullable String computePathWithTypes(@NotNull RepoPath path, @NotNull List<VaultPackageAssembler> packageAssemblers) {
        path = new RepoPath(PlatformNameFormat.getPlatformPath(path.toString()));

        boolean type = false;
        String current = "";
        for (String part : path.toString().substring(1).split("/")) {
            current += current.isEmpty() ? part : "/" + part;
            for (VaultPackageAssembler packageAssembler : packageAssemblers) {
                File currentContent = packageAssembler.getEntry(current + "/" + CONTENT_XML_FILE_NAME);
                if (currentContent.isFile()) {
                    String primary;
                    String mixin;
                    try (FileInputStream input = new FileInputStream(currentContent);
                        FileInputStream input2 = new FileInputStream(currentContent)) {
                        primary = new PrimaryTypeParser().parse(input);
                        mixin = new MixinParser().parse(input2);
                        current += "(" + primary;
                        if (mixin != null) {
                            mixin = mixin.trim();
                            if (mixin.startsWith("[")) {
                                mixin = mixin.substring(1, mixin.length() - 1);
                            }
                            current += " mixin " + mixin;
                        }
                        current += ")";
                        type = true;
                    } catch (Exception e) {
                        throw new RuntimeException("A fatal error occurred while parsing the '"
                                + currentContent
                                + "' file, see nested exceptions: "
                                + e);
                    }
                }
            }
        }

        return type ? new RepoPath(current).toString() : null;
    }

    private static void addAclStatement(@NotNull Formatter formatter, @NotNull SystemUser systemUser, @NotNull List<AccessControlEntry> authorizations) {
        if (authorizations.isEmpty()) {
            return;
        }

        writeAccessControl(authorizations, "set ACL for %s%n", systemUser, formatter);
    }

    private static void writeAccessControl(@NotNull List<AccessControlEntry> accessControlEntries, @NotNull String statement, @NotNull SystemUser systemUser, @NotNull Formatter formatter) {
        formatter.format(statement, systemUser.getId());
        writeEntries(accessControlEntries, systemUser, formatter);
        formatter.format("end%n");
    }

    private static void writeEntries(@NotNull List<AccessControlEntry> accessControlEntries, @NotNull SystemUser systemUser, @NotNull Formatter formatter) {
        for (AccessControlEntry entry : accessControlEntries) {
            formatter.format("%s %s on %s",
                    entry.getOperation(),
                    entry.getPrivileges(),
                    getRepoInitPath(entry.getRepositoryPath(), systemUser));

            if (!entry.getRestrictions().isEmpty()) {
                formatter.format(" restriction(%s)",
                        String.join(",", entry.getRestrictions()));
            }

            formatter.format("%n");
        }
    }

    @NotNull
    private static String getRepoInitPath(@NotNull RepoPath path, @NotNull SystemUser systemUser) {
        // FIXME SLING-9953 : add special handing for path pointing to the system-user home or some other user/group home
        if (path.isRepositoryPath()) {
            return ":repository";
        } else {
            return path.toString();
        }
    }

    private static void registerPrivileges(@NotNull PrivilegeDefinitions definitions, @NotNull Formatter formatter) {
        NameResolver nameResolver = new DefaultNamePathResolver(definitions.getNamespaceMapping());
        for (PrivilegeDefinition privilege : definitions.getDefinitions()) {
            try {
                String name = nameResolver.getJCRName(privilege.getName());
                String aggregates = getAggregatedNames(privilege, nameResolver);
                if (privilege.isAbstract()) {
                    formatter.format("register abstract privilege %s%s%n", name, aggregates);
                } else {
                    formatter.format("register privilege %s%s%n", name, aggregates);
                }
            } catch (NamespaceException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @NotNull
    private static String getAggregatedNames(@NotNull PrivilegeDefinition definition, @NotNull NameResolver nameResolver) {
        Set<Name> aggregatedNames = definition.getDeclaredAggregateNames();
        if (aggregatedNames.isEmpty()) {
            return "";
        } else {
            Set<String> names = aggregatedNames.stream().map(name -> {
                try {
                    return nameResolver.getJCRName(name);
                } catch (NamespaceException e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toSet());
            return " with "+String.join(",", names);
        }
    }
}
