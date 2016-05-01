/*
 * Copyright (c) 2016 Villu Ruusmann
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

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import org.dmg.pmml.DataField;
import org.dmg.pmml.Entity;
import org.dmg.pmml.FeatureType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.FieldUsageType;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.Output;
import org.dmg.pmml.OutputField;
import org.dmg.pmml.PMMLObject;
import org.dmg.pmml.Target;
import org.dmg.pmml.Value;
import org.jpmml.model.visitors.FieldReferenceFinder;

public class ModelUtil {

	private ModelUtil(){
	}

	static
	public MiningSchema createMiningSchema(List<DataField> dataFields){
		return createMiningSchema(dataFields, null);
	}

	static
	public MiningSchema createMiningSchema(List<DataField> dataFields, PMMLObject object){
		return createMiningSchema(dataFields.get(0), dataFields.subList(1, dataFields.size()), object);
	}

	static
	public MiningSchema createMiningSchema(DataField targetDataField, List<DataField> activeDataFields){
		return createMiningSchema(targetDataField, activeDataFields, null);
	}

	static
	public MiningSchema createMiningSchema(DataField targetDataField, List<DataField> activeDataFields, PMMLObject object){
		Function<DataField, FieldName> function = new Function<DataField, FieldName>(){

			@Override
			public FieldName apply(DataField dataField){

				if(dataField == null){
					return null;
				}

				return dataField.getName();
			}
		};

		return createMiningSchema(function.apply(targetDataField), Lists.transform(activeDataFields, function), object);
	}

	static
	public MiningSchema createMiningSchema(Schema schema){
		return createMiningSchema(schema, null);
	}

	static
	public MiningSchema createMiningSchema(Schema schema, PMMLObject object){
		return createMiningSchema(schema.getTargetField(), schema.getActiveFields(), object);
	}

	static
	public MiningSchema createMiningSchema(FieldName targetField, List<FieldName> activeFields){
		return createMiningSchema(targetField, activeFields, null);
	}

	static
	public MiningSchema createMiningSchema(FieldName targetField, List<FieldName> activeFields, PMMLObject object){

		if(object != null){
			FieldReferenceFinder fieldReferenceFinder = new FieldReferenceFinder();
			fieldReferenceFinder.applyTo(object);

			activeFields = new ArrayList<>(activeFields);
			activeFields.retainAll(fieldReferenceFinder.getFieldNames());
		}

		List<MiningField> miningFields = new ArrayList<>();

		if(targetField != null){
			miningFields.add(createMiningField(targetField, FieldUsageType.TARGET));
		}

		Function<FieldName, MiningField> function = new Function<FieldName, MiningField>(){

			@Override
			public MiningField apply(FieldName name){
				return createMiningField(name);
			}
		};

		miningFields.addAll(Lists.transform(activeFields, function));

		MiningSchema miningSchema = new MiningSchema(miningFields);

		return miningSchema;
	}

	static
	public MiningField createMiningField(FieldName name){
		return createMiningField(name, null);
	}

	static
	public MiningField createMiningField(FieldName name, FieldUsageType usageType){
		MiningField miningField = new MiningField(name)
			.setUsageType(usageType);

		return miningField;
	}

	static
	public Target createRescaleTarget(DataField dataField, Double slope, Double intercept){
		return createRescaleTarget(dataField.getName(), slope, intercept);
	}

	static
	public Target createRescaleTarget(FieldName name, Double slope, Double intercept){
		Target target = new Target(name);

		if(slope != null && !ValueUtil.isOne(slope)){
			target.setRescaleFactor(slope);
		} // End if

		if(intercept != null && !ValueUtil.isZero(intercept)){
			target.setRescaleConstant(intercept);
		}

		return target;
	}

	static
	public Output createProbabilityOutput(DataField dataField){

		if(!dataField.hasValues()){
			return null;
		}

		Output output = new Output(createProbabilityFields(dataField));

		return output;
	}

	static
	public Output createProbabilityOutput(Schema schema){
		List<String> targetCategories = schema.getTargetCategories();

		if(targetCategories == null || targetCategories.isEmpty()){
			return null;
		}

		Output output = new Output(createProbabilityFields(targetCategories));

		return output;
	}

	static
	public OutputField createAffinityField(String value){
		return createAffinityField(FieldName.create("affinity_" + value), value);
	}

	static
	public OutputField createAffinityField(FieldName name, String value){
		OutputField outputField = new OutputField(name)
			.setFeature(FeatureType.AFFINITY)
			.setValue(value);

		return outputField;
	}

	static
	public List<OutputField> createAffinityFields(List<? extends Entity> entities){
		Function<Entity, OutputField> function = new Function<Entity, OutputField>(){

			@Override
			public OutputField apply(Entity entity){
				return createAffinityField(entity.getId());
			}
		};

		return Lists.newArrayList(Lists.transform(entities, function));
	}

	static
	public OutputField createEntityIdField(FieldName name){
		OutputField outputField = new OutputField(name)
			.setFeature(FeatureType.ENTITY_ID);

		return outputField;
	}

	static
	public OutputField createPredictedField(FieldName name){
		OutputField outputField = new OutputField(name)
			.setFeature(FeatureType.PREDICTED_VALUE);

		return outputField;
	}

	static
	public OutputField createProbabilityField(String value){
		return createProbabilityField(FieldName.create("probability_" + value), value);
	}

	static
	public OutputField createProbabilityField(FieldName name, String value){
		OutputField outputField = new OutputField(name)
			.setFeature(FeatureType.PROBABILITY)
			.setValue(value);

		return outputField;
	}

	static
	public List<OutputField> createProbabilityFields(DataField dataField){
		List<Value> values = dataField.getValues();

		Function<Value, OutputField> function = new Function<Value, OutputField>(){

			@Override
			public OutputField apply(Value value){
				return createProbabilityField(value.getValue());
			}
		};

		return Lists.newArrayList(Lists.transform(values, function));
	}

	static
	public List<OutputField> createProbabilityFields(List<String> values){
		Function<String, OutputField> function = new Function<String, OutputField>(){

			@Override
			public OutputField apply(String value){
				return createProbabilityField(value);
			}
		};

		return Lists.newArrayList(Lists.transform(values, function));
	}
}