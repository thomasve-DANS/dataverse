/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.harvard.iq.dataverse.engine.command.impl;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersionUser;
import edu.harvard.iq.dataverse.DvObject;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.engine.command.AbstractCommand;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.exception.IllegalCommandException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.concurrent.Future;

/**
 *
 * @author skraffmi
 */
@RequiredPermissions(Permission.EditDataset)
public class SubmitDatasetForReviewCommand extends AbstractCommand<Dataset> {

    private final Dataset theDataset;
    
    public SubmitDatasetForReviewCommand(DataverseRequest aRequest, DvObject anAffectedDvObject) {
        super(aRequest, anAffectedDvObject);
        this.theDataset = (Dataset) anAffectedDvObject;
    }


    @Override
    public Dataset execute(CommandContext ctxt) throws CommandException {
                
        if (theDataset == null) {
            throw new IllegalCommandException("Can't submit for review. Dataset is null.", this);
        } 
                
        if (theDataset.getLatestVersion().isReleased()) {
            throw new IllegalCommandException("Latest version of dataset " + theDataset.getIdentifier() + " is already released. Only draft versions can be submitted for review.", this);
        }   
        
        return save(ctxt);        
    }
    
    public Dataset save(CommandContext ctxt)  throws CommandException {


        Timestamp updateTime = new Timestamp(new Date().getTime());
        theDataset.getEditVersion().setLastUpdateTime(updateTime);
        theDataset.setModificationTime(updateTime);


        Dataset savedDataset = ctxt.em().merge(theDataset);
        ctxt.em().flush();

        /**
         * @todo What should we do with the indexing result? Print it to the
         * log?
         */
        boolean doNormalSolrDocCleanUp = true;
        Future<String> indexingResult = ctxt.index().indexDataset(savedDataset, doNormalSolrDocCleanUp);
        DatasetVersionUser ddu = ctxt.datasets().getDatasetVersionUser(theDataset.getLatestVersion(), this.getUser());

        if (ddu != null) {
            ddu.setLastUpdateDate(updateTime);
            ctxt.em().merge(ddu);
        } else {
            DatasetVersionUser datasetDataverseUser = new DatasetVersionUser();
            datasetDataverseUser.setDatasetVersion(savedDataset.getLatestVersion());
            datasetDataverseUser.setLastUpdateDate((Timestamp) updateTime); 
            String id = getUser().getIdentifier();
            id = id.startsWith("@") ? id.substring(1) : id;
            AuthenticatedUser au = ctxt.authentication().getAuthenticatedUser(id);
            datasetDataverseUser.setAuthenticatedUser(au);
            ctxt.em().merge(datasetDataverseUser);
        }
        return savedDataset;
    }
    
}
