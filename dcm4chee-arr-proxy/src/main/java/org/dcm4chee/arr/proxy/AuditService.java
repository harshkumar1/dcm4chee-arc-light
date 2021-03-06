/*
 * ** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2016
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ** END LICENSE BLOCK *****
 */

package org.dcm4chee.arr.proxy;

import org.dcm4che3.audit.*;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.audit.AuditLogger;
import org.dcm4che3.net.audit.AuditLoggerDeviceExtension;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.conf.Duration;
import org.keycloak.KeycloakSecurityContext;
import org.keycloak.adapters.RefreshableKeycloakSecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Jan 2017
 */
public class AuditService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
    private static final Map<String, Long> aggregate = new LinkedHashMap<>();
    private static final String keycloakClassName = "org.keycloak.KeycloakSecurityContext";

    public static void auditLogUsed(Device device, HttpServletRequest httpRequest) {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        Duration auditAggregateDuration = arcDev.getAuditAggregateDuration();
        if (auditAggregateDuration != null) {
            long now = System.currentTimeMillis();
            String remoteAddr = httpRequest.getRemoteAddr();
            Long emitted = aggregate.get(remoteAddr);
            if (emitted != null && now < emitted)
                return;

            for (Iterator<Long> iter = aggregate.values().iterator(); iter.hasNext() && iter.next() < now; )
                iter.remove();

            aggregate.put(remoteAddr, now + auditAggregateDuration.getSeconds() * 1000L);
        }
        AuditLoggerDeviceExtension ext = device.getDeviceExtension(AuditLoggerDeviceExtension.class);
        if (ext == null || ext.getAuditLoggers().isEmpty())
            return;

        for (AuditLogger logger : ext.getAuditLoggers())
            emitAudit(logger, httpRequest, arcDev);
    }

    private static void emitAudit(AuditLogger logger, HttpServletRequest request, ArchiveDeviceExtension arcDev) {
        String userId = request.getAttribute(keycloakClassName) != null
                ? getPreferredUsername(request) : request.getRemoteAddr();
        AuditMessage msg = new AuditMessage();
        EventIdentification ei = AuditMessages.createEventIdentification(AuditMessages.EventID.AuditLogUsed,
                AuditMessages.EventActionCode.Read, logger.timeStamp(), AuditMessages.EventOutcomeIndicator.Success,
                null);
        ActiveParticipant ap = AuditMessages.createActiveParticipant(userId, logger.processID(), null, true,
                request.getRemoteHost(), AuditMessages.NetworkAccessPointTypeCode.IPAddress, null);
        ParticipantObjectIdentification poi = AuditMessages.createParticipantObjectIdentification(
                arcDev.getAuditRecordRepositoryURL(), AuditMessages.ParticipantObjectIDTypeCode.URI,
                "Security Audit Log", null, AuditMessages.ParticipantObjectTypeCode.SystemObject,
                AuditMessages.ParticipantObjectTypeCodeRole.SecurityResource, null, null, null);
        msg.setEventIdentification(ei);
        msg.getActiveParticipant().add(ap);
        msg.getAuditSourceIdentification().add(logger.createAuditSourceIdentification());
        msg.getParticipantObjectIdentification().add(poi);
        try {
            logger.write(logger.timeStamp(), msg);
        } catch (Exception e) {
            LOG.warn("Failed to emit audit message", logger.getCommonName(), e);
        }
    }

    private static String getPreferredUsername(HttpServletRequest req) {
        RefreshableKeycloakSecurityContext securityContext = (RefreshableKeycloakSecurityContext)
                req.getAttribute(KeycloakSecurityContext.class.getName());
        return securityContext.getToken().getPreferredUsername();
    }
}
