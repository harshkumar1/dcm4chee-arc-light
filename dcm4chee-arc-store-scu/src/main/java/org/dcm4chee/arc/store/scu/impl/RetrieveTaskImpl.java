/*
 * *** BEGIN LICENSE BLOCK *****
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
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015
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
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.store.scu.impl;

import org.dcm4che3.data.*;
import org.dcm4che3.imageio.codec.Transcoder;
import org.dcm4che3.io.TemplatesCache;
import org.dcm4che3.io.XSLTAttributesCoercion;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.RetrieveTask;
import org.dcm4che3.util.SafeClose;
import org.dcm4che3.util.StringUtils;
import org.dcm4chee.arc.conf.ArchiveAEExtension;
import org.dcm4chee.arc.conf.ArchiveAttributeCoercion;
import org.dcm4chee.arc.conf.Duration;
import org.dcm4chee.arc.retrieve.InstanceLocations;
import org.dcm4chee.arc.retrieve.RetrieveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.Templates;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Aug 2015
 */
final class RetrieveTaskImpl implements RetrieveTask {

    static final Logger LOG = LoggerFactory.getLogger(RetrieveTaskImpl.class);

    private final RetrieveContext ctx;
    private final Association storeas;
    private final ArchiveAEExtension aeExt;
    private final String hostName;
    private Dimse dimserq;
    private Association rqas;
    private PresentationContext pc;
    private Attributes rqCmd;
    private int msgId;
    private boolean pendingRSP;
    private Duration pendingRSPInterval;
    private final Collection<InstanceLocations> outstandingRSP =
            Collections.synchronizedCollection(new ArrayList<InstanceLocations>());
    private volatile boolean canceled;
    private ScheduledFuture<?> writePendingRSP;

    RetrieveTaskImpl(RetrieveContext ctx, Association storeas) {
        this.ctx = ctx;
        this.storeas = storeas;
        this.aeExt = ctx.getArchiveAEExtension();
        this.hostName = storeas.getSocket().getInetAddress().getHostName();
    }

    void setRequestAssociation(Dimse dimserq, Association rqas, PresentationContext pc, Attributes rqCmd) {
        this.dimserq = dimserq;
        this.rqas = rqas;
        this.pc = pc;
        this.rqCmd = rqCmd;
        this.msgId = rqCmd.getInt(Tag.MessageID, 0);
        this.pendingRSP = dimserq == Dimse.C_GET_RQ && aeExt.sendPendingCGet();
        this.pendingRSPInterval = dimserq == Dimse.C_MOVE_RQ ? aeExt.sendPendingCMoveInterval() : null;
    }

    @Override
    public void onCancelRQ(Association association) {
        canceled = true;
    }

    @Override
    public void run() {
        if (rqas != null)
            rqas.addCancelRQHandler(msgId, this);
        try {
            startWritePendingRSP();
            for (InstanceLocations match : ctx.getMatches()) {
                if (canceled)
                    break;
                store(match);
            }
            waitForOutstandingCStoreRSP();
            if (rqas != null)
                writeFinalRSP();
        } finally {
            releaseStoreAssociation();
            stopWritePendingRSP();
            if (rqas != null)
                rqas.removeCancelRQHandler(msgId);
            SafeClose.close(ctx);
        }
    }

    private void store(InstanceLocations inst) {
        CStoreRSPHandler rspHandler = new CStoreRSPHandler(inst);
        String iuid = inst.getSopInstanceUID();
        String cuid = inst.getSopClassUID();
        int priority = ctx.getPriority();
        Set<String> tsuids = storeas.getTransferSyntaxesFor(cuid);
        try {
            if (tsuids.isEmpty()) {
                throw new NoPresentationContextException(cuid);
            }
            try (Transcoder transcoder = ctx.getRetrieveService().openTranscoder(ctx, inst, tsuids, false)) {
                String tsuid = transcoder.getDestinationTransferSyntax();
                DataWriter data = new TranscoderDataWriter(transcoder,
                        new MergeAttributesCoercion(inst.getAttributes(), coercion(cuid)));
                outstandingRSP.add(inst);
                if (ctx.getMoveOriginatorAETitle() != null) {
                    storeas.cstore(cuid, iuid, priority,
                            ctx.getMoveOriginatorAETitle(), ctx.getMoveOriginatorMessageID(),
                            data, tsuid, rspHandler);
                } else {
                    storeas.cstore(cuid, iuid, priority,
                            data, tsuid, rspHandler);
                }
            }
        } catch (Exception e) {
            outstandingRSP.remove(inst);
            ctx.addFailedSOPInstanceUID(inst.getSopInstanceUID());
            LOG.info("{}: failed to send {} to {}:", rqas, inst, ctx.getDestinationAETitle(), e);
        }
    }

    private AttributesCoercion coercion(String cuid) throws Exception {
        ArchiveAttributeCoercion coercion = aeExt.findAttributeCoercion(
                hostName, storeas.getRemoteAET(), TransferCapability.Role.SCP, Dimse.C_STORE_RQ, cuid);
        if (coercion == null)
            return null;
        LOG.debug("{}: Apply {}", storeas, coercion);
        String uri = StringUtils.replaceSystemProperties(coercion.getXSLTStylesheetURI());
        Templates tpls = TemplatesCache.getDefault().get(uri);
        return new XSLTAttributesCoercion(tpls, null)
                .includeKeyword(!coercion.isNoKeywords());
    }

    private void writeFinalRSP() {
        int remaining = ctx.remaining();
        writeRSP(remaining > 0 ? Status.Cancel : ctx.status(), remaining, finalRSPDataset());
    }

    private Attributes finalRSPDataset() {
        if (ctx.failed() == 0)
            return null;

        Attributes attrs = new Attributes(1);
        attrs.setString(Tag.FailedSOPInstanceUIDList, VR.UI, ctx.failedSOPInstanceUIDs());
        return attrs;
    }

    private void writePendingRSP() {
        if (canceled)
            return;

        int remaining = ctx.remaining();
        if (remaining > 0)
            writeRSP(Status.Pending, remaining, null);
    }

    private void writeRSP(int status, int remaining, Attributes data) {
        Attributes cmd = Commands.mkRSP(rqCmd, status, dimserq);
        if (remaining > 0)
            cmd.setInt(Tag.NumberOfRemainingSuboperations, VR.US, remaining);
        cmd.setInt(Tag.NumberOfCompletedSuboperations, VR.US, ctx.completed());
        cmd.setInt(Tag.NumberOfFailedSuboperations, VR.US, ctx.failed());
        cmd.setInt(Tag.NumberOfWarningSuboperations, VR.US, ctx.warning());
        try {
            rqas.writeDimseRSP(pc, cmd, data);
        } catch (IOException e) {
            LOG.warn("{}: Unable to send C-GET or C-MOVE RSP on association to {}",
                    rqas, rqas.getRemoteAET(), e);
        }
    }

    private void startWritePendingRSP() {
        if (pendingRSP)
            writePendingRSP();
        if (pendingRSPInterval != null)
            writePendingRSP = rqas.getApplicationEntity().getDevice()
                    .scheduleAtFixedRate(
                            new Runnable() {
                                @Override
                                public void run() {
                                    writePendingRSP();
                                }
                            },
                            0, pendingRSPInterval.getSeconds(), TimeUnit.SECONDS);
    }

    private void stopWritePendingRSP() {
        if (writePendingRSP != null)
            writePendingRSP.cancel(false);
    }

    private void waitForOutstandingCStoreRSP() {
        try {
            synchronized (outstandingRSP) {
                while (!outstandingRSP.isEmpty())
                    outstandingRSP.wait();
            }
        } catch (InterruptedException e) {
            LOG.warn("{}: failed to wait for outstanding RSP on association to {}", rqas, storeas.getRemoteAET(), e);
        }
    }

    private void removeOutstandingRSP(InstanceLocations inst) {
        outstandingRSP.remove(inst);
        synchronized (outstandingRSP) {
            outstandingRSP.notify();
        }
    }

    protected void releaseStoreAssociation() {
        if (dimserq != Dimse.C_GET_RQ)
            try {
                storeas.release();
            } catch (IOException e) {
                LOG.warn("{}: failed to release association to {}", rqas, storeas.getRemoteAET(), e);
            }
    }

    private final class CStoreRSPHandler extends DimseRSPHandler {

        private final InstanceLocations inst;

        public CStoreRSPHandler(InstanceLocations inst) {
            super(storeas.nextMessageID());
            this.inst = inst;
        }

        @Override
        public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
            super.onDimseRSP(as, cmd, data);
            int storeStatus = cmd.getInt(Tag.Status, -1);
            if (storeStatus == Status.Success)
                ctx.incrementCompleted();
            else if ((storeStatus & 0xB000) == 0xB000)
                ctx.incrementWarning();
            else {
                ctx.addFailedSOPInstanceUID(inst.getSopInstanceUID());
            }
            if (pendingRSP)
                writePendingRSP();
            removeOutstandingRSP(inst);
        }

        @Override
        public void onClose(Association as) {
            super.onClose(as);
            removeOutstandingRSP(inst);
        }
    }

}
