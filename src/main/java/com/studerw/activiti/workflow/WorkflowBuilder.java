package com.studerw.activiti.workflow;

import com.google.common.collect.Lists;
import com.studerw.activiti.model.document.DocType;
import com.studerw.activiti.model.workflow.DynamicUserTask;
import com.studerw.activiti.model.workflow.DynamicUserTaskType;
import org.activiti.bpmn.BpmnAutoLayout;
import org.activiti.bpmn.model.*;
import org.activiti.bpmn.model.Process;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.repository.ProcessDefinition;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Used to build and modify workflows for addition of dynamic tasks
 *
 * @author William Studer
 *         Date: 5/29/14
 */
@Service("workflowBuilder")
public class WorkflowBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowBuilder.class);

    @Autowired RepositoryService repoSrvc;
    @Autowired WorkflowService workflowService;

    /**
     *
     * @param docType
     * @param group
     * @return a newly created process definition cloned from the base {@link com.studerw.activiti.model.document.DocType}.
     * The process definition should be deployed to the repo with diagramming also created.
     * An {@code IllegalStateException} is thrown if the group workflow already exits.
     */
    public ProcessDefinition createGroupWorkflow(DocType docType, String group) {
        LOG.info("Creating new workflow for docType {} and group: {}", docType, group);
        String key = WFConstants.createProcId(docType, group);

        //make sure the docType is valid
        if (!this.workflowService.baseDocTypeWorkflowExists(docType)) {
            throw new IllegalStateException("The doctype: " + docType.name() + " has no base workflow definition.");
        }

        //make sure one doesn't already exist.
        if (workflowService.groupWorkflowExists(docType, group)) {
            throw new IllegalStateException("The workflow for doctype: " + docType.name() + " and group: " + group + " already exists");
        }

        ProcessDefinition baseProcDef = this.workflowService.findBaseProcDef(docType);

        BpmnModel base = this.repoSrvc.getBpmnModel(baseProcDef.getId());
        org.activiti.bpmn.model.Process proc = base.getMainProcess();

        BpmnModel clone = new BpmnModel();
        clone.setTargetNamespace(WFConstants.NAMESPACE_CATEGORY);
        proc.setId(key);
        proc.setName(String.format("%s for group %s", docType.name(), group));
        clone.addProcess(proc);

        //create the diagramming
        new BpmnAutoLayout(clone).execute();

        String deployId = this.repoSrvc.createDeployment()
                .addBpmnModel(key + ".bpmn", clone).name("Dynamic Process Deployment - " + key).deploy().getId();


        ProcessDefinition procDef = workflowService.findProcDefByDocTypeAndGroup(docType, group);
        Assert.notNull(procDef, "something went wrong creating the new processDefinition: " + key);
        return procDef;
    }

    /**
     *
     * @param procDef
     * @return a list of ordered {@code DynamicUserTask} found within the {@code Dynamic Subprocess}
     */
    public List<DynamicUserTask> getDynamicTasks(ProcessDefinition procDef) {
        Assert.notNull(procDef, "ProcessDefinition cannot be null");
        LOG.debug("returning dynamic tasks for procDef: {}", procDef.getKey());

        BpmnModel model = repoSrvc.getBpmnModel(procDef.getId());
        Process process = model.getMainProcess();

        SubProcess sub = (SubProcess) process.getFlowElement(WFConstants.SUBPROCESS_ID_DYNAMIC);
        LOG.trace(sub.getName());
        List<DynamicUserTask> tasks = Lists.newArrayList();

        Collection<FlowElement> flowElements = sub.getFlowElements();
        int i = 1;
        for (FlowElement el : flowElements) {
            String id = el.getId();
            if (isDynamicUserTask(id)) {
                UserTask userTask = (UserTask)el;
                DynamicUserTask dut = fromUserTask(userTask, i);
                LOG.debug("Adding {}", id);
                tasks.add(dut);

            }
        }
        //this may not be needed, though it's dependent up on Activiti's internal API
        Collections.sort(tasks);
        return tasks;
    }


    /**
     * Build the minimal base document definition needed for dynamic tasks ({@code NONE} group is used).
     * The ID is set to {@code WFConstants.WORKFLOW_GROUP_NONE} and
     * the category (i.e. Namespace) set to {@code DocType.GENERAL};
     *
     * @param name
     * @return BpmnModel
     */
    public BpmnModel defaultDocument(String name) {
        BpmnModel model = new BpmnModel();
        model.setTargetNamespace(WFConstants.NAMESPACE_CATEGORY);
        org.activiti.bpmn.model.Process process = new Process();
        process.setId(WFConstants.createProcId(DocType.UNIT_TEST_NO_EXIST, WFConstants.WORKFLOW_GROUP_NONE));
                process.setName(name);
        model.addProcess(process);

        StartEvent startEvent = new StartEvent();
        startEvent.setId("start");

        process.addFlowElement(startEvent);

        org.activiti.bpmn.model.UserTask submitTask = new org.activiti.bpmn.model.UserTask();
        submitTask.setId("submitDocUserTask");
        submitTask.setName("Submit Document to Workflow");
        process.addFlowElement(submitTask);
        process.addFlowElement(createSequenceFlow(startEvent.getId(), submitTask.getId()));

        ErrorEventDefinition errorDef = new ErrorEventDefinition();
        errorDef.setErrorCode(WFConstants.ERROR_DOC_REJECTED);

        SubProcess sub = createEmptyDynamicSubProcess(errorDef);
        process.addFlowElement(sub);

        process.addFlowElement(createSequenceFlow(submitTask.getId(), sub.getId()));

        BoundaryEvent boundaryEvent = new BoundaryEvent();
        boundaryEvent.setId(WFConstants.REJECTED_BOUNDARY_EVENT_ID);
        boundaryEvent.setName("Rejected Error Event");
        boundaryEvent.setAttachedToRef(sub);
        boundaryEvent.addEventDefinition(errorDef);
        process.addFlowElement(boundaryEvent);

        process.addFlowElement(createSequenceFlow(boundaryEvent.getId(), submitTask.getId(), "Rejected"));
/*
        ServiceTask pub = new ServiceTask();
        pub.setId("publishDocServiceTask");
        pub.setName("Publish Approved Document");
        pub.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        pub.setImplementation("${documentWorkflow.publish(execution)}");

        process.addFlowElement(pub);
*/

        EndEvent endEvent = new EndEvent();
        endEvent.setId("end");
        process.addFlowElement(endEvent);
        process.addFlowElement(createSequenceFlow(sub.getId(), endEvent.getId()));

//        process.addFlowElement(createSequenceFlow(pub.getId(), endEvent.getId()));

        //Generate graphical information
        //TODOnew BpmnAutoLayout(model).execute();
        return model;
    }

    /**
     *
     * @param docType
     * @param group
     * @param tasks
     * @return the newly created ProcessDefinition of the replaced workflow for the given {@code DocType} and group.
     */
    public ProcessDefinition updateDynamicTasks(DocType docType, String group, List<DynamicUserTask> tasks) {
        LOG.info("updating tasks for docType {} and group: {}", docType, group);
        String key = WFConstants.createProcId(docType, group);

        //make sure one doesn't already exist.
        if (!workflowService.groupWorkflowExists(docType, group)) {
            throw new IllegalStateException("The workflow for doctype: " + docType.name() + " and group: " + group + " does not exist");
        }

        ProcessDefinition procDef = workflowService.findProcDefByDocTypeAndGroup(docType, group);

        BpmnModel model = this.repoSrvc.getBpmnModel(procDef.getId());
        Process proc = model.getMainProcess();
        Process clone = proc.clone();
        SubProcess subProcessOrig = this.getDynamicSubProcess(clone);
        if (subProcessOrig == null) {
            throw new IllegalStateException("Could not find the required Dynamic SubProcess for docType: " + docType + " and group: " + group);
        }
        ErrorEventDefinition error = this.getErrorEventDefinition(clone);
        if (error == null) {
            throw new IllegalStateException("Could not find the ErrorEventDefinition for docType: " + docType + " and group: " + group);
        }
        SubProcess subProcessUpDt = createDynamicSubProcess(tasks, error);
        List<SequenceFlow> sequenceFlows = getSubReferences(clone, subProcessOrig, subProcessUpDt);
        //only need to modify ref from original subProcess to next flow element
        clone.addFlowElement(subProcessUpDt);

        BpmnModel updatedModel = new BpmnModel();
        updatedModel.setTargetNamespace(WFConstants.NAMESPACE_CATEGORY);
        updatedModel.addProcess(clone);

        //create the diagramming
        String deployId = this.repoSrvc.createDeployment()
                .addBpmnModel(key + ".bpmn", updatedModel).name("Dynamic Process Deployment - " + key).deploy().getId();
        ProcessDefinition updatedProcDef = workflowService.findProcDefByDocTypeAndGroup(docType, group);

        new BpmnAutoLayout(this.repoSrvc.getBpmnModel(updatedProcDef.getId())).execute();

        Assert.notNull(updatedProcDef, "something went wrong creating the new processDefinition: " + key);
        return updatedProcDef;
    }


    /**
     * @param dynamicUserTasks
     * @param docType
     * @param group
     * @return fully populated BpmnModel with appropriate ids, namespace, sub process tasks, etc.
     */
    public BpmnModel documentWithTasks(List<DynamicUserTask> dynamicUserTasks, DocType docType, String group) {
        Assert.notNull(docType);
        Assert.hasText(group);
        BpmnModel model = new BpmnModel();
        model.setTargetNamespace(WFConstants.NAMESPACE_CATEGORY);
        org.activiti.bpmn.model.Process process = new Process();
        process.setId(String.format("%s_%s", docType.name(), group));
        process.setName(String.format("Generated workflow for docType=%s and Group=%s", docType.name(), group));

        model.addProcess(process);

        StartEvent startEvent = new StartEvent();
        startEvent.setId("start");
        process.addFlowElement(startEvent);

        org.activiti.bpmn.model.UserTask submitTask = new org.activiti.bpmn.model.UserTask();
        submitTask.setId("submitDocUserTask");
        submitTask.setName("Submit doc to Workflow");
        process.addFlowElement(submitTask);
        process.addFlowElement(createSequenceFlow(startEvent.getId(), submitTask.getId()));

        ErrorEventDefinition errorDef = new ErrorEventDefinition();
        errorDef.setErrorCode(WFConstants.ERROR_DOC_REJECTED);

        SubProcess sub = createDynamicSubProcess(dynamicUserTasks, errorDef);
        process.addFlowElement(sub);

        process.addFlowElement(createSequenceFlow(submitTask.getId(), sub.getId()));

        BoundaryEvent boundaryEvent = new BoundaryEvent();
        boundaryEvent.setId(WFConstants.REJECTED_BOUNDARY_EVENT_ID);
        boundaryEvent.setName("Rejected Error Event");
        boundaryEvent.setAttachedToRef(sub);
        boundaryEvent.addEventDefinition(errorDef);
        process.addFlowElement(boundaryEvent);

        process.addFlowElement(createSequenceFlow(boundaryEvent.getId(), submitTask.getId(), "Rejected"));
/*
        ServiceTask pub = new ServiceTask();
        pub.setId("publishDocServiceTask");
        pub.setName("Publish Approved Document");
        pub.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        pub.setImplementation("${documentWorkflow.publish(execution)}");

        process.addFlowElement(pub);
        process.addFlowElement(createSequenceFlow(sub.getId(), pub.getId()));
*/

        EndEvent endEvent = new EndEvent();
        endEvent.setId("end");

        process.addFlowElement(endEvent);
        process.addFlowElement(createSequenceFlow(sub.getId(), endEvent.getId()));

        //Generate graphical information
        new BpmnAutoLayout(model).execute();
        return model;
    }


    protected SubProcess createEmptyDynamicSubProcess(ErrorEventDefinition errorDef) {
        SubProcess sub = new SubProcess();
        sub.setId(WFConstants.SUBPROCESS_ID_DYNAMIC);
        sub.setName(WFConstants.SUBPROCESS_NAME_DYNAMIC);

        StartEvent start = new StartEvent();
        start.setId("dynamic_sub_process_start_event");
        start.setName("Start Dynamic SubProcess");
        sub.addFlowElement(start);

        EndEvent end = new EndEvent();
        end.setId("dynamic_sub_process_end_event");
        end.setName("End Dynamic SubProcess");
        sub.addFlowElement(end);

        EndEvent errorEnd = new EndEvent();
        errorEnd.setId("rejectedErrorEndEvent");
        errorEnd.setName("ErrorEnd");
        errorEnd.addEventDefinition(errorDef);
        sub.addFlowElement(errorEnd);

        sub.addFlowElement(createSequenceFlow(start.getId(), end.getId()));

        return sub;

    }

    protected SubProcess createDynamicSubProcess(List<DynamicUserTask> dynamicUserTasks, ErrorEventDefinition errorDef) {
        SubProcess sub = new SubProcess();
        sub.setId(WFConstants.SUBPROCESS_ID_DYNAMIC);
        sub.setName(WFConstants.SUBPROCESS_NAME_DYNAMIC);

        StartEvent start = new StartEvent();
        start.setId("dynamic_sub_process_start_event");
        start.setName("Start Dynamic SubProcess");
        sub.addFlowElement(start);

        EndEvent end = new EndEvent();
        end.setId("dynamic_sub_process_end_event");
        end.setName("End Dynamic SubProcess");
        sub.addFlowElement(end);

        EndEvent errorEnd = new EndEvent();
        errorEnd.setId("rejectedErrorEndEvent");
        errorEnd.setName("ErrorEnd");
        errorEnd.addEventDefinition(errorDef);
        sub.addFlowElement(errorEnd);

        if (dynamicUserTasks.isEmpty()) {
            sub.addFlowElement(createSequenceFlow(start.getId(), end.getId()));
            return sub;
        }


        SequenceFlow startFlow = new SequenceFlow(start.getId(), null);
        sub.addFlowElement(startFlow);
        RecurseUserTasks recurseUserTasks = new RecurseUserTasks(dynamicUserTasks, sub, errorEnd);

        SequenceFlow lastRef = recurseUserTasks.recurseSequenceFlows(startFlow);
        lastRef.setTargetRef(end.getId());
        return sub;
    }



    protected static SequenceFlow approvalTask(SubProcess sub, EndEvent errorEnd, DynamicUserTask from, int currentIdx, int total, SequenceFlow prev) {
        org.activiti.bpmn.model.UserTask current = new org.activiti.bpmn.model.UserTask();
        current.setId(String.format("%s_%d", WFConstants.TASK_ID_DOC_APPROVAL, currentIdx));
        if (StringUtils.isBlank(from.getName())) {
            current.setName(String.format("Approve Document (%d / %d)", currentIdx, total));
        } else {
            current.setName(from.getName());
        }

        ActivitiListener onCreateApproval = new ActivitiListener();
        onCreateApproval.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        onCreateApproval.setImplementation("${docWorkflowListener.onCreateApproval(execution, task)}");
        onCreateApproval.setEvent("create");
        current.setTaskListeners(Lists.newArrayList(onCreateApproval));


        if (from.getCandidateGroups().isEmpty() && from.getCandidateUsers().isEmpty()) {
            throw new IllegalArgumentException("user task does not have any candidate users / groups assigned");
/*
                ActivitiListener taskListener = new ActivitiListener();
                taskListener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
                taskListener.setImplementation("${documentWorkflow.setAssignee(execution, task)}");
                taskListener.setEvent("create");
                userTask.setTaskListeners(Lists.newArrayList(taskListener));
*/
        }
        current.setCandidateGroups(from.getCandidateGroups());
        current.setCandidateUsers(from.getCandidateUsers());

        ExclusiveGateway gw = new ExclusiveGateway();
        gw.setId(String.format("exclusivegateway_approval_%d_of_%d", currentIdx, total));
        gw.setName(String.format("Exclusive Approval Gateway %d of %d", currentIdx, total));
        sub.addFlowElement(gw);
        sub.addFlowElement(createSequenceFlow(current.getId(), gw.getId()));

        //-------------------------------------------------------------
        SequenceFlow rejectedFlow = new SequenceFlow();
        rejectedFlow.setId(String.format("docRejectedSubFlow_%d_of_%d", currentIdx, total));
        rejectedFlow.setName(String.format("Doc Rejected %d of %d", currentIdx, total));
        rejectedFlow.setSourceRef(gw.getId());
        rejectedFlow.setTargetRef(errorEnd.getId());

        ActivitiListener rejectedListener = new ActivitiListener();
        rejectedListener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        rejectedListener.setImplementation("${documentWorkflow.onRejected(execution)}");
        rejectedListener.setEvent("take");
        rejectedFlow.setExecutionListeners(Lists.newArrayList(rejectedListener));
        rejectedFlow.setConditionExpression("${approved == false}");
        sub.addFlowElement(rejectedFlow);

        //-----------------------------------------------
        SequenceFlow approvedFlow = new SequenceFlow();
        approvedFlow.setId(String.format("docApprovedSubFlow_%d_of_%d", currentIdx, total));
        approvedFlow.setName(String.format("Doc Approved %d of %d", currentIdx, total));
        approvedFlow.setSourceRef(gw.getId());

        ActivitiListener approvedListener = new ActivitiListener();
        approvedListener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        approvedListener.setImplementation("${documentWorkflow.onApproved(execution)}");
        approvedListener.setEvent("take");
        approvedFlow.setExecutionListeners(Lists.newArrayList(approvedListener));
        approvedFlow.setConditionExpression("${approved == true}");
        sub.addFlowElement(approvedFlow);

        sub.addFlowElement(current);
        prev.setTargetRef(current.getId());
        return approvedFlow;
    }

    protected static SequenceFlow collaborationTask(SubProcess subProcess, DynamicUserTask from, int currentIdx, int total, SequenceFlow prev) {
        org.activiti.bpmn.model.UserTask current = new org.activiti.bpmn.model.UserTask();
        current.setId(String.format("%s_%d", WFConstants.TASK_ID_DOC_COLLABORATE, currentIdx));
        if (StringUtils.isBlank(from.getName())) {
            current.setName(String.format("Document Collaboration (%d / %d)", currentIdx, total));
        } else {
            current.setName(from.getName());
        }

        ActivitiListener onCreate = new ActivitiListener();
        onCreate.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        onCreate.setImplementation("${docWorkflowListener.onCreateCollaborate(execution, task)}");
        onCreate.setEvent("create");

        ActivitiListener onComplete = new ActivitiListener();
        onComplete.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_EXPRESSION);
        onComplete.setImplementation("${docWorkflowListener.onCompleteCollaborate(execution, task)}");
        onComplete.setEvent("complete");

        current.setTaskListeners(Lists.newArrayList(onCreate, onComplete));
        subProcess.addFlowElement(current);
        prev.setTargetRef(current.getId());

        SequenceFlow ref = new SequenceFlow();
        ref.setId(String.format("dynamic_collab_subflow_%d_%d", currentIdx, total));
        ref.setName(String.format("Collaboration SubFlow %d of %d", currentIdx, total));
        ref.setSourceRef(current.getId());

        return ref;
    }

    protected static SequenceFlow createSequenceFlow(String from, String to) {
        SequenceFlow flow = new SequenceFlow();
        flow.setSourceRef(from);
        flow.setTargetRef(to);
        return flow;
    }

    protected static SequenceFlow createSequenceFlow(String from, String to, String name) {
        SequenceFlow flow = new SequenceFlow();
        flow.setSourceRef(from);
        flow.setTargetRef(to);
        flow.setName(name);
        return flow;
    }

    protected DynamicUserTask fromUserTask(org.activiti.bpmn.model.UserTask userTask, int position) {
        DynamicUserTask dut = new DynamicUserTask();
        dut.setIndex(position);
        dut.setCandidateGroups(Lists.newArrayList(userTask.getCandidateGroups()));
        dut.setCandidateUsers(Lists.newArrayList(userTask.getCandidateUsers()));
        dut.setName(userTask.getName());
        dut.setId(userTask.getId());
        dut.setDynamicUserTaskType(fromString(dut.getId()));
        return dut;
    }

    protected DynamicUserTaskType fromString(String id){
        if (StringUtils.startsWith(id, WFConstants.TASK_ID_DOC_APPROVAL)){
            return DynamicUserTaskType.APPROVE_REJECT;
        }
        else if (StringUtils.startsWith(id, WFConstants.TASK_ID_DOC_COLLABORATE)){
            return DynamicUserTaskType.COLLABORATION;
        }
        else return null;
    }

    protected boolean isDynamicUserTask(String id) {
        return StringUtils.startsWith(id, WFConstants.TASK_ID_DOC_APPROVAL) ||
                StringUtils.startsWith(id, WFConstants.TASK_ID_DOC_COLLABORATE);

    }

    protected SubProcess getDynamicSubProcess(Process process) {
        FlowElement sub = process.getFlowElement(WFConstants.SUBPROCESS_ID_DYNAMIC);
        if (sub != null){
            return (SubProcess)sub;
        }
        return null;
    }

    protected ErrorEventDefinition getErrorEventDefinition(Process process){
        FlowElement boundary = process.getFlowElement(WFConstants.REJECTED_BOUNDARY_EVENT_ID);
        if (boundary == null) {
            return null;
        }
        BoundaryEvent boundaryEvent = (BoundaryEvent) boundary;
        List<EventDefinition> eventDefs = boundaryEvent.getEventDefinitions();
        for (EventDefinition eventDef : eventDefs) {
            if (eventDef instanceof ErrorEventDefinition){
                ErrorEventDefinition temp = (ErrorEventDefinition)eventDef;
                if (WFConstants.ERROR_DOC_REJECTED.equals(temp.getErrorCode())){
                    return temp;
                }
            }
        }
        return  null;
    }

    /**
     * @param process
     * @param original
     * @param updated
     * @return
     */
    private List<SequenceFlow> getSubReferences(Process process, SubProcess original, SubProcess updated) {
        List<SequenceFlow> refs = Lists.newArrayList();

        Collection<FlowElement> flowElements = process.getFlowElements();
        for (FlowElement el : flowElements) {
            if (el instanceof SequenceFlow){
                SequenceFlow seqFlow = (SequenceFlow)el;
                if (WFConstants.SUBPROCESS_ID_DYNAMIC.equals(seqFlow.getTargetRef())){
                    refs.add(0, seqFlow);
                    continue;
                }
                if (WFConstants.SUBPROCESS_ID_DYNAMIC.equals(seqFlow.getSourceRef())) {
                    refs.add(1, seqFlow);
                    continue;
                }
            }
        }
        if (refs.size() != 2 || refs.get(0) == null || refs.get(1) == null) {
            throw new IllegalStateException("Could not find source and ref sequenceflows");
        }
        return refs;
    }

    private static class RecurseUserTasks{
        private List<DynamicUserTask> dynamicUserTasks;
        private int approvalCount = 1;
        private int approvalsTotal = 0;
        private int collabCount = 1;
        private int collabTotal = 0;
        private SubProcess subProcess;
        private EndEvent errorEnd;

        public RecurseUserTasks(List<DynamicUserTask> dynamicUserTasks, SubProcess subProcess, EndEvent errorEnd) {
            this.dynamicUserTasks = Lists.newArrayList(dynamicUserTasks);
            this.subProcess = subProcess;
            this.errorEnd = errorEnd;
            List<DynamicUserTask> collaborations = Lists.newArrayList();
            List<DynamicUserTask> approvals = Lists.newArrayList();
            for (DynamicUserTask ut : dynamicUserTasks) {
                if (DynamicUserTaskType.APPROVE_REJECT.equals(ut.getDynamicUserTaskType())) {
                    this.approvalsTotal++;
                } else if (DynamicUserTaskType.COLLABORATION.equals(ut.getDynamicUserTaskType())) {
                    this.collabTotal++;
                } else {
                    throw new IllegalArgumentException("Invalid user task type: " + ut.getDynamicUserTaskType());
                }
            }
        }

        public SequenceFlow recurseSequenceFlows(SequenceFlow prev){
            if (dynamicUserTasks.isEmpty()){
                return prev;
            }
            DynamicUserTask current = this.dynamicUserTasks.remove(0);
            SequenceFlow target;
            if (DynamicUserTaskType.APPROVE_REJECT.equals(current.getDynamicUserTaskType())) {
                 target = approvalTask(subProcess, errorEnd, current, approvalCount, approvalsTotal, prev);
                approvalCount++;
            } else if (DynamicUserTaskType.COLLABORATION.equals(current.getDynamicUserTaskType())) {
                target = collaborationTask(subProcess, current, collabCount, collabTotal, prev);
                collabCount++;
            } else {
                throw new IllegalArgumentException("Invalid user task type: " + current.getDynamicUserTaskType());
            }
            return recurseSequenceFlows(target);
        }
    }
}
