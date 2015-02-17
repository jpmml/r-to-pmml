/*
 * Copyright (c) 2015 Villu Ruusmann
 *
 * This file is part of JPMML-Converter
 *
 * JPMML-Converter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Converter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Converter.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.converter;

import java.util.List;

import com.beust.jcommander.internal.Lists;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.dmg.pmml.Array;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldUsageType;
import org.dmg.pmml.Header;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningFunctionType;
import org.dmg.pmml.MiningModel;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.MultipleModelMethodType;
import org.dmg.pmml.Node;
import org.dmg.pmml.OpType;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.Segment;
import org.dmg.pmml.Segmentation;
import org.dmg.pmml.SimplePredicate;
import org.dmg.pmml.SimpleSetPredicate;
import org.dmg.pmml.Target;
import org.dmg.pmml.Targets;
import org.dmg.pmml.TreeModel;
import org.dmg.pmml.TreeModel.SplitCharacteristic;
import org.dmg.pmml.True;
import org.dmg.pmml.Value;
import rexp.Rexp;
import rexp.Rexp.STRING;

public class GBMConverter extends Converter {

	private List<DataField> dataFields = Lists.newArrayList();

	private LoadingCache<ElementKey, SimpleSetPredicate> predicateCache = CacheBuilder.newBuilder()
		.build(new CacheLoader<ElementKey, SimpleSetPredicate>(){

			@Override
			public SimpleSetPredicate load(ElementKey key){
				Object[] content = key.getContent();

				return encodeSimpleSetPredicate((DataField)content[0], (List<Integer>)content[1], (Boolean)content[2]);
			}
		});


	@Override
	public PMML convert(Rexp.REXP gbm){
		Rexp.REXP initF = REXPUtil.field(gbm, "initF");
		Rexp.REXP trees = REXPUtil.field(gbm, "trees");
		Rexp.REXP c_splits = REXPUtil.field(gbm, "c.splits");
		Rexp.REXP response_name = REXPUtil.field(gbm, "response.name");
		Rexp.REXP var_levels = REXPUtil.field(gbm, "var.levels");
		Rexp.REXP var_names = REXPUtil.field(gbm, "var.names");
		Rexp.REXP var_type = REXPUtil.field(gbm, "var.type");

		initFields(response_name, var_names, var_type, var_levels);

		List<Segment> segments = Lists.newArrayList();

		for(int i = 0; i < trees.getRexpValueCount(); i++){
			Rexp.REXP tree = trees.getRexpValue(i);

			TreeModel treeModel = encodeTreeModel(MiningFunctionType.REGRESSION, tree, c_splits);

			Segment segment = new Segment()
				.withId(String.valueOf(i + 1))
				.withPredicate(new True())
				.withModel(treeModel);

			segments.add(segment);
		}

		Segmentation segmentation = new Segmentation(MultipleModelMethodType.SUM, segments);

		MiningSchema miningSchema = new MiningSchema();

		for(int i = 0; i < this.dataFields.size(); i++){
			DataField dataField = this.dataFields.get(i);

			MiningField miningField = new MiningField()
				.withName(dataField.getName())
				.withUsageType(i > 0 ? FieldUsageType.ACTIVE : FieldUsageType.TARGET);

			miningSchema = miningSchema.withMiningFields(miningField);
		}

		DataField dataField = this.dataFields.get(0);

		Target target = new Target()
			.withField(dataField.getName())
			.withRescaleConstant(REXPUtil.asDouble(initF.getRealValue(0)));

		Targets targets = new Targets()
			.withTargets(target);

		MiningModel miningModel = new MiningModel(MiningFunctionType.REGRESSION, miningSchema)
			.withSegmentation(segmentation)
			.withTargets(targets);

		DataDictionary dataDictionary = new DataDictionary()
			.withDataFields(this.dataFields);

		PMML pmml = new PMML("4.2", new Header(), dataDictionary)
			.withModels(miningModel);

		return pmml;
	}

	private void initFields(Rexp.REXP response_name, Rexp.REXP var_names, Rexp.REXP var_type, Rexp.REXP var_levels){

		// Dependent variable
		{
			STRING name = response_name.getStringValue(0);

			DataField dataField = PMMLUtil.createDataField(name.getStrval(), DataType.DOUBLE);

			this.dataFields.add(dataField);
		}

		// Independent variables
		for(int i = 0; i < var_names.getStringValueCount(); i++){
			STRING var_name = var_names.getStringValue(i);

			boolean categorical = (var_type.getRealValue(i) > 0d);

			DataField dataField = PMMLUtil.createDataField(var_name.getStrval(), categorical);

			if(categorical){
				List<Value> values = dataField.getValues();

				Rexp.REXP var_level = var_levels.getRexpValue(i);

				for(int j = 0; j < var_level.getStringValueCount(); j++){
					STRING level = var_level.getStringValue(j);

					values.add(new Value(level.getStrval()));
				}

				dataField = PMMLUtil.refineDataField(dataField);
			}

			this.dataFields.add(dataField);
		}
	}

	private TreeModel encodeTreeModel(MiningFunctionType miningFunction, Rexp.REXP tree, Rexp.REXP c_splits){
		Node root = new Node()
			.withId("1")
			.withPredicate(new True());

		encodeNode(root, 0, tree, c_splits);

		FieldCollector fieldCollector = new TreeModelFieldCollector();
		fieldCollector.applyTo(root);

		List<MiningField> activeFields = PMMLUtil.createMiningFields(fieldCollector);

		MiningSchema miningSchema = new MiningSchema()
			.withMiningFields(activeFields);

		TreeModel treeModel = new TreeModel(miningFunction, miningSchema, root)
			.withSplitCharacteristic(SplitCharacteristic.MULTI_SPLIT);

		return treeModel;
	}

	private void encodeNode(Node node, int i, Rexp.REXP tree, Rexp.REXP c_splits){
		Rexp.REXP splitVar = tree.getRexpValue(0);
		Rexp.REXP splitCodePred = tree.getRexpValue(1);
		Rexp.REXP leftNode = tree.getRexpValue(2);
		Rexp.REXP rightNode = tree.getRexpValue(3);
		Rexp.REXP missingNode = tree.getRexpValue(4);
		Rexp.REXP prediction = tree.getRexpValue(7);

		Predicate missingPredicate = null;

		Predicate leftPredicate = null;
		Predicate rightPredicate = null;

		Integer var = splitVar.getIntValue(i);
		if(var != -1){
			DataField dataField = this.dataFields.get(var + 1);

			missingPredicate = encodeIsMissingPredicate(dataField);

			Double split = splitCodePred.getRealValue(i);

			OpType opType = dataField.getOpType();
			switch(opType){
				case CATEGORICAL:
					Integer index = REXPUtil.asInteger(split);

					Rexp.REXP c_split = c_splits.getRexpValue(index);

					List<Integer> splitValues = c_split.getIntValueList();

					leftPredicate = this.predicateCache.getUnchecked(new ElementKey(dataField, splitValues, Boolean.TRUE));
					rightPredicate = this.predicateCache.getUnchecked(new ElementKey(dataField, splitValues, Boolean.FALSE));
					break;
				case CONTINUOUS:
					leftPredicate = encodeSimplePredicate(dataField, split, true);
					rightPredicate = encodeSimplePredicate(dataField, split, false);
					break;
				default:
					throw new IllegalArgumentException();
			}
		} else

		{
			Double value = prediction.getRealValue(i);

			node = node.withScore(PMMLUtil.formatValue(value));
		}

		Integer missing = missingNode.getIntValue(i);
		if(missing != -1){
			Node missingChild = new Node()
				.withId(String.valueOf(missing + 1))
				.withPredicate(missingPredicate);

			encodeNode(missingChild, missing, tree, c_splits);

			node = node.withNodes(missingChild);
		}

		Integer left = leftNode.getIntValue(i);
		if(left != -1){
			Node leftChild = new Node()
				.withId(String.valueOf(left + 1))
				.withPredicate(leftPredicate);

			encodeNode(leftChild, left, tree, c_splits);

			node = node.withNodes(leftChild);
		}

		Integer right = rightNode.getIntValue(i);
		if(right != -1){
			Node rightChild = new Node()
				.withId(String.valueOf(right + 1))
				.withPredicate(rightPredicate);

			encodeNode(rightChild, right, tree, c_splits);

			node = node.withNodes(rightChild);
		}
	}

	private SimplePredicate encodeIsMissingPredicate(DataField dataField){
		SimplePredicate simplePredicate = new SimplePredicate()
			.withField(dataField.getName())
			.withOperator(SimplePredicate.Operator.IS_MISSING);

		return simplePredicate;
	}

	private SimpleSetPredicate encodeSimpleSetPredicate(DataField dataField, List<Integer> splitValues, boolean left){
		SimpleSetPredicate simpleSetPredicate = new SimpleSetPredicate()
			.withField(dataField.getName())
			.withBooleanOperator(SimpleSetPredicate.BooleanOperator.IS_IN)
			.withArray(encodeArray(dataField, splitValues, left));

		return simpleSetPredicate;
	}

	private Array encodeArray(DataField dataField, List<Integer> splitValues, boolean left){
		List<Value> values = selectValues(dataField.getValues(), splitValues, left);

		return PMMLUtil.createArray(dataField.getDataType(), values);
	}

	private SimplePredicate encodeSimplePredicate(DataField dataField, Double split, boolean left){
		SimplePredicate simplePredicate = new SimplePredicate()
			.withField(dataField.getName())
			.withOperator(left ? SimplePredicate.Operator.LESS_THAN : SimplePredicate.Operator.GREATER_OR_EQUAL)
			.withValue(PMMLUtil.formatValue(split));

		return simplePredicate;
	}

	static
	private List<Value> selectValues(List<Value> values, List<Integer> splitValues, boolean left){

		if(values.size() != splitValues.size()){
			throw new IllegalArgumentException();
		}

		List<Value> result = Lists.newArrayList();

		for(int i = 0; i < values.size(); i++){
			Value value = values.get(i);

			boolean append;

			if(left){
				append = (splitValues.get(i) == -1);
			} else

			{
				append = (splitValues.get(i) == 1);
			} // End if

			if(append){
				result.add(value);
			}
		}

		return result;
	}
}